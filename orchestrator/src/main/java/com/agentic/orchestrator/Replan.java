package com.agentic.orchestrator;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Set;
import java.util.TreeSet;

public final class Replan {
    private Replan() {}

    @FunctionalInterface
    public interface Transition {
        void apply(RunState state, String nodeId, String from, String to, String reason);
    }

    public static Set<String> staleDownstream(Workflow workflow, RunState state) {
        Set<String> stale = new TreeSet<>();
        for (String nodeId : workflow.nodes.keySet()) {
            ContextEntry entry = state.context.latest(nodeId);
            if (entry == null) {
                continue;
            }
            for (var iv : entry.inputVersions.entrySet()) {
                ContextEntry latestDep = state.context.latest(iv.getKey());
                if (latestDep != null && latestDep.version != iv.getValue()) {
                    stale.add(nodeId);
                    break;
                }
            }
        }
        Deque<String> frontier = new ArrayDeque<>(stale);
        while (!frontier.isEmpty()) {
            String current = frontier.pop();
            for (String dependentId : workflow.dependentsOf(current)) {
                if (stale.add(dependentId)) {
                    frontier.push(dependentId);
                }
            }
        }
        return stale;
    }

    public static Set<String> invalidateStale(Workflow workflow, RunState state, Transition transition) {
        Set<String> stale = staleDownstream(workflow, state);
        Set<String> invalidated = new TreeSet<>();
        for (String nodeId : stale) {
            StageStatus status = state.nodeStatus.get(nodeId);
            if (status == StageStatus.PASSED || status == StageStatus.AWAITING_APPROVAL) {
                transition.apply(state, nodeId, status.value(), "invalidated", "stale input version");
                transition.apply(state, nodeId, "invalidated", "pending", "re-queued after replan");
                invalidated.add(nodeId);
            }
        }
        return invalidated;
    }
}
