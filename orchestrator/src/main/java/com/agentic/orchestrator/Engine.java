package com.agentic.orchestrator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class Engine {
    private final Workflow workflow;
    private final RunStore store;

    public Engine(Workflow workflow, RunStore store) {
        this.workflow = workflow;
        this.store = store;
    }

    public RunState start(String runId) {
        RunState state = new RunState();
        state.runId = runId;
        state.workflowName = workflow.name;
        state.nodeStatus = new LinkedHashMap<>();
        state.nodeAttempts = new LinkedHashMap<>();
        for (String nodeId : workflow.nodes.keySet()) {
            state.nodeStatus.put(nodeId, StageStatus.PENDING);
            state.nodeAttempts.put(nodeId, 0);
        }
        state.context = new RunContext(runId);
        state.startedAt = Timestamps.nowIso();
        store.save(state);
        return runLoop(state);
    }

    public RunState resume(String runId) {
        RunState state = store.load(runId);
        state.safeStopRequested = false;
        for (var e : new ArrayList<>(state.nodeStatus.entrySet())) {
            if (e.getValue() == StageStatus.STOPPED) {
                transition(state, e.getKey(), "stopped", "pending", "resumed");
            }
        }
        store.save(state);
        return runLoop(state);
    }

    public RunState stop(String runId) {
        RunState state = store.load(runId);
        state.safeStopRequested = true;
        store.save(state);
        return state;
    }

    public RunState approve(String runId, String nodeId, String note) {
        RunState state = store.load(runId);
        if (state.nodeStatus.get(nodeId) != StageStatus.AWAITING_APPROVAL) {
            throw new IllegalArgumentException(nodeId + " is not awaiting approval");
        }
        transition(state, nodeId, "awaiting_approval", "passed", "approved: " + note);
        store.save(state);
        return runLoop(state);
    }

    public RunState reject(String runId, String nodeId, String note, String reviseStage) {
        RunState state = store.load(runId);
        if (state.nodeStatus.get(nodeId) != StageStatus.AWAITING_APPROVAL) {
            throw new IllegalArgumentException(nodeId + " is not awaiting approval");
        }
        transition(state, nodeId, "awaiting_approval", "pending", "rejected: " + note);
        if (!reviseStage.equals(nodeId)) {
            StageStatus current = state.nodeStatus.getOrDefault(reviseStage, StageStatus.PENDING);
            transition(state, reviseStage, current.value(), "pending", "revise requested: " + note);
        }
        store.save(state);
        return runLoop(state);
    }

    private EventLog events(String runId) {
        return new EventLog(store.eventsPath(runId));
    }

    private void transition(RunState state, String nodeId, String from, String to, String reason) {
        synchronized (state) {
            state.nodeStatus.put(nodeId, StageStatus.fromValue(to));
            events(state.runId).append(new Event(
                    state.runId, nodeId, from, to, Timestamps.nowIso(),
                    state.nodeAttempts.getOrDefault(nodeId, 1), reason));
        }
    }

    private List<String> readyNodes(RunState state) {
        List<String> ready = new ArrayList<>();
        for (var entry : workflow.nodes.entrySet()) {
            String nodeId = entry.getKey();
            StageNode node = entry.getValue();
            if (state.nodeStatus.get(nodeId) != StageStatus.PENDING) {
                continue;
            }
            boolean depsPassed = true;
            for (String dep : node.dependsOn) {
                if (state.nodeStatus.get(dep) != StageStatus.PASSED) {
                    depsPassed = false;
                    break;
                }
            }
            if (depsPassed) {
                ready.add(nodeId);
            }
        }
        return ready;
    }

    private RunState runLoop(RunState state) {
        while (true) {
            boolean safeStop;
            synchronized (state) {
                safeStop = state.safeStopRequested;
            }
            if (safeStop) {
                for (var e : new ArrayList<>(state.nodeStatus.entrySet())) {
                    if (e.getValue() == StageStatus.PENDING) {
                        transition(state, e.getKey(), "pending", "stopped", "safe-stop requested");
                    }
                }
                store.save(state);
                break;
            }
            List<String> ready = readyNodes(state);
            if (ready.isEmpty()) {
                break;
            }
            ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, ready.size()));
            try {
                List<Future<?>> futures = new ArrayList<>();
                for (String nodeId : ready) {
                    futures.add(pool.submit(() -> {
                        executeNode(state, nodeId);
                        return null;
                    }));
                }
                for (Future<?> f : futures) {
                    f.get();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            } catch (ExecutionException e) {
                throw new RuntimeException(e.getCause());
            } finally {
                pool.shutdown();
            }
            store.save(state);
            boolean awaiting = false;
            for (String n : ready) {
                if (state.nodeStatus.get(n) == StageStatus.AWAITING_APPROVAL) {
                    awaiting = true;
                    break;
                }
            }
            if (awaiting) {
                break;
            }
        }
        boolean anyPending = false;
        for (StageStatus s : state.nodeStatus.values()) {
            if (s == StageStatus.PENDING) {
                anyPending = true;
                break;
            }
        }
        if (!anyPending) {
            state.finishedAt = Timestamps.nowIso();
            store.save(state);
        }
        return state;
    }

    private void executeNode(RunState state, String nodeId) {
        StageNode node = workflow.nodes.get(nodeId);
        transition(state, nodeId, "pending", "running", "");

        GateResult entryResult;
        try {
            entryResult = node.entryGate.apply(state.context);
        } catch (Exception e) {
            entryResult = GateResult.fail("gate raised " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        if (!"ok".equals(entryResult.outcome)) {
            transition(state, nodeId, "running", "blocked", entryResult.reason);
            return;
        }

        RetryOutcome ro = runWithRetry(node, state);
        StageResult result = ro.result();
        boolean usedFallback = ro.usedFallback();

        GateResult exitResult;
        try {
            exitResult = node.exitGate.apply(result, state.context);
        } catch (Exception e) {
            exitResult = GateResult.fail("gate raised " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        if ("needs_approval".equals(exitResult.outcome)) {
            recordContext(state, node, result, usedFallback);
            Replan.invalidateStale(workflow, state, this::transition);
            transition(state, nodeId, "running", "awaiting_approval", exitResult.reason);
            return;
        }

        if ("pass".equals(exitResult.outcome)) {
            recordContext(state, node, result, usedFallback);
            Replan.invalidateStale(workflow, state, this::transition);
            if (node.requiresApproval) {
                transition(state, nodeId, "running", "awaiting_approval", "human approval required");
            } else {
                transition(state, nodeId, "running", "passed", "");
            }
            return;
        }

        if (node.rollback != null) {
            node.rollback.accept(state.context);
            transition(state, nodeId, "running", "rolled_back", exitResult.reason);
        } else {
            transition(state, nodeId, "running", "failed", exitResult.reason);
        }
        blockDownstream(state, nodeId);
    }

    private record RetryOutcome(StageResult result, boolean usedFallback) {}

    private RetryOutcome runWithRetry(StageNode node, RunState state) {
        RetryPolicy policy = node.retryPolicy;
        StageResult lastResult = new StageResult(StageStatus.FAILED, "no attempts executed");
        for (int attempt = 1; attempt <= policy.maxAttempts; attempt++) {
            synchronized (state) {
                state.nodeAttempts.put(node.id, attempt);
            }
            try {
                lastResult = node.executor.run(state.context);
            } catch (Exception e) {
                lastResult = new StageResult(StageStatus.FAILED,
                        "executor raised " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
            if (lastResult.status == StageStatus.PASSED) {
                return new RetryOutcome(lastResult, false);
            }
            if (attempt < policy.maxAttempts && policy.backoffSeconds > 0) {
                try {
                    Thread.sleep((long) (policy.backoffSeconds * 1000));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
            }
        }
        if (node.fallbackExecutor != null) {
            StageResult fallbackResult;
            try {
                fallbackResult = node.fallbackExecutor.run(state.context);
            } catch (Exception e) {
                fallbackResult = new StageResult(StageStatus.FAILED,
                        "executor raised " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
            return new RetryOutcome(fallbackResult, true);
        }
        return new RetryOutcome(lastResult, false);
    }

    private void recordContext(RunState state, StageNode node, StageResult result, boolean usedFallback) {
        int version = state.context.nextVersion(node.id);
        StageExecutor producer = usedFallback ? node.fallbackExecutor : node.executor;
        Map<String, Integer> inputVersions = new LinkedHashMap<>();
        for (String dep : node.dependsOn) {
            ContextEntry latest = state.context.latest(dep);
            if (latest != null) {
                inputVersions.put(dep, latest.version);
            }
        }
        state.context.append(new ContextEntry(
                node.id, version, result.outputs,
                producer.getClass().getSimpleName(),
                result.notes, Timestamps.nowIso(), inputVersions));
    }

    private void blockDownstream(RunState state, String nodeId) {
        for (String downstreamId : workflow.allDownstream(nodeId)) {
            if (state.nodeStatus.get(downstreamId) == StageStatus.PENDING) {
                transition(state, downstreamId, "pending", "blocked", "upstream " + nodeId + " did not pass");
            }
        }
    }
}
