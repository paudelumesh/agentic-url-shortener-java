package com.agentic.orchestrator.cli;

import com.agentic.orchestrator.Engine;
import com.agentic.orchestrator.Event;
import com.agentic.orchestrator.EventLog;
import com.agentic.orchestrator.Metrics;
import com.agentic.orchestrator.RunState;
import com.agentic.orchestrator.RunStore;
import com.agentic.orchestrator.StageStatus;
import com.agentic.orchestrator.Workflow;
import com.agentic.orchestrator.scenarios.ScenarioRegistry;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.Function;

@Command(name = "orchestrate", mixinStandardHelpOptions = true, subcommands = {
        OrchestrateCli.RunCmd.class,
        OrchestrateCli.StatusCmd.class,
        OrchestrateCli.ApproveCmd.class,
        OrchestrateCli.RejectCmd.class,
        OrchestrateCli.ResumeCmd.class,
        OrchestrateCli.StopCmd.class,
        OrchestrateCli.MetricsCmd.class
})
public class OrchestrateCli implements Runnable {
    static final Path RUNS_DIR = Paths.get("runs");

    public static void main(String[] args) {
        System.exit(new CommandLine(new OrchestrateCli()).execute(args));
    }

    @Override
    public void run() {
        new CommandLine(this).usage(System.out);
    }

    static Workflow buildWorkflow(String name, Path targetDir) {
        Function<Path, Workflow> builder = ScenarioRegistry.REGISTRY.get(name);
        if (builder == null) {
            List<String> available = new ArrayList<>(ScenarioRegistry.REGISTRY.keySet());
            Collections.sort(available);
            throw new NoSuchElementException("unknown scenario '" + name + "'. Available: " + available);
        }
        return builder.apply(targetDir);
    }

    static Path targetDirMarker(String runId) {
        return RUNS_DIR.resolve(runId).resolve("target_dir.txt");
    }

    static void saveTargetDir(String runId, Path targetDir) {
        if (targetDir == null) {
            return;
        }
        try {
            Path marker = targetDirMarker(runId);
            Files.createDirectories(marker.getParent());
            Files.writeString(marker, targetDir.toString());
        } catch (IOException e) {
            throw new IllegalStateException("cannot save target dir marker: " + e.getMessage(), e);
        }
    }

    static Path loadTargetDir(String runId) {
        Path marker = targetDirMarker(runId);
        if (!Files.exists(marker)) {
            return null;
        }
        try {
            return Path.of(Files.readString(marker).strip());
        } catch (IOException e) {
            throw new IllegalStateException("cannot read target dir marker: " + e.getMessage(), e);
        }
    }

    static void printStatus(RunState state) {
        System.out.println("run: " + state.runId + "  workflow: " + state.workflowName);
        for (Map.Entry<String, StageStatus> entry : state.nodeStatus.entrySet()) {
            System.out.println("  [" + String.format("%17s", entry.getValue().value()) + "] " + entry.getKey());
        }
    }

    static void fail(RuntimeException e) {
        System.err.println("error: " + e.getMessage());
        System.exit(1);
    }

    @Command(name = "run", description = "Start a scenario run")
    static class RunCmd implements Runnable {
        @Parameters(index = "0", paramLabel = "SCENARIO", description = "Scenario name")
        String scenario;

        @Option(names = "--run-id", description = "Run id (default: <scenario>-<epochSeconds>)")
        String runId;

        @Option(names = "--target-dir", description = "Disposable workspace copy of the urlshortener repo")
        String targetDir;

        @Override
        public void run() {
            try {
                Function<Path, Workflow> builder = ScenarioRegistry.REGISTRY.get(scenario);
                if (builder == null) {
                    List<String> available = new ArrayList<>(ScenarioRegistry.REGISTRY.keySet());
                    Collections.sort(available);
                    throw new NoSuchElementException(
                            "unknown scenario '" + scenario + "'. Available: " + available);
                }
                Path target = targetDir != null ? Path.of(targetDir).toAbsolutePath() : null;
                Workflow workflow;
                try {
                    workflow = builder.apply(target);
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("--target-dir is required to run scenario '"
                            + scenario + "' (" + e.getMessage() + ")", e);
                }
                RunStore store = new RunStore(RUNS_DIR);
                Engine engine = new Engine(workflow, store);
                String id = runId != null ? runId : scenario + "-" + Instant.now().getEpochSecond();
                saveTargetDir(id, target);
                printStatus(engine.start(id));
            } catch (IllegalArgumentException | NoSuchElementException | IllegalStateException e) {
                fail(e);
            }
        }
    }

    @Command(name = "status", description = "Show run status")
    static class StatusCmd implements Runnable {
        @Parameters(index = "0", paramLabel = "RUN_ID", description = "Run id")
        String runId;

        @Override
        public void run() {
            try {
                RunStore store = new RunStore(RUNS_DIR);
                printStatus(store.load(runId));
            } catch (IllegalArgumentException | NoSuchElementException | IllegalStateException e) {
                fail(e);
            }
        }
    }

    @Command(name = "approve", description = "Approve a node awaiting approval")
    static class ApproveCmd implements Runnable {
        @Parameters(index = "0", paramLabel = "RUN_ID", description = "Run id")
        String runId;

        @Parameters(index = "1", paramLabel = "NODE", description = "Node id")
        String node;

        @Option(names = "--note", description = "Approval note", defaultValue = "")
        String note;

        @Override
        public void run() {
            try {
                RunStore store = new RunStore(RUNS_DIR);
                RunState state = store.load(runId);
                Workflow workflow = buildWorkflow(state.workflowName, loadTargetDir(runId));
                Engine engine = new Engine(workflow, store);
                printStatus(engine.approve(runId, node, note));
            } catch (IllegalArgumentException | NoSuchElementException | IllegalStateException e) {
                fail(e);
            }
        }
    }

    @Command(name = "reject", description = "Reject a node awaiting approval")
    static class RejectCmd implements Runnable {
        @Parameters(index = "0", paramLabel = "RUN_ID", description = "Run id")
        String runId;

        @Parameters(index = "1", paramLabel = "NODE", description = "Node id")
        String node;

        @Option(names = "--note", description = "Rejection note", defaultValue = "")
        String note;

        @Option(names = "--revise", description = "Stage to revise", required = true)
        String revise;

        @Override
        public void run() {
            try {
                RunStore store = new RunStore(RUNS_DIR);
                RunState state = store.load(runId);
                Workflow workflow = buildWorkflow(state.workflowName, loadTargetDir(runId));
                Engine engine = new Engine(workflow, store);
                printStatus(engine.reject(runId, node, note, revise));
            } catch (IllegalArgumentException | NoSuchElementException | IllegalStateException e) {
                fail(e);
            }
        }
    }

    @Command(name = "resume", description = "Resume a run")
    static class ResumeCmd implements Runnable {
        @Parameters(index = "0", paramLabel = "RUN_ID", description = "Run id")
        String runId;

        @Override
        public void run() {
            try {
                RunStore store = new RunStore(RUNS_DIR);
                RunState state = store.load(runId);
                Workflow workflow = buildWorkflow(state.workflowName, loadTargetDir(runId));
                Engine engine = new Engine(workflow, store);
                printStatus(engine.resume(runId));
            } catch (IllegalArgumentException | NoSuchElementException | IllegalStateException e) {
                fail(e);
            }
        }
    }

    @Command(name = "stop", description = "Stop a run")
    static class StopCmd implements Runnable {
        @Parameters(index = "0", paramLabel = "RUN_ID", description = "Run id")
        String runId;

        @Override
        public void run() {
            try {
                RunStore store = new RunStore(RUNS_DIR);
                RunState state = store.load(runId);
                Workflow workflow = buildWorkflow(state.workflowName, loadTargetDir(runId));
                Engine engine = new Engine(workflow, store);
                printStatus(engine.stop(runId));
            } catch (IllegalArgumentException | NoSuchElementException | IllegalStateException e) {
                fail(e);
            }
        }
    }

    @Command(name = "metrics", description = "Show aggregate metrics")
    static class MetricsCmd implements Runnable {
        @Option(names = "--run", description = "Single run id (default: all runs)")
        String run;

        @Override
        public void run() {
            try {
                RunStore store = new RunStore(RUNS_DIR);
                List<String> runIds = run != null ? List.of(run) : store.listRuns();
                Map<String, List<Event>> allEvents = new LinkedHashMap<>();
                for (String id : runIds) {
                    allEvents.put(id, new EventLog(store.eventsPath(id)).readAll());
                }
                Map<String, Object> metrics = Metrics.aggregateMetrics(allEvents);
                System.out.println(new ObjectMapper().writerWithDefaultPrettyPrinter()
                        .writeValueAsString(metrics));
            } catch (JsonProcessingException e) {
                fail(new IllegalStateException("cannot serialize metrics: " + e.getMessage(), e));
            } catch (IllegalArgumentException | NoSuchElementException | IllegalStateException e) {
                fail(e);
            }
        }
    }
}
