package com.agentic.orchestrator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.agentic.orchestrator.support.RecordingExecutor;

import org.junit.jupiter.api.Test;

class ReplanTest {

    private static Workflow workflow() {
        return new Workflow("replan", List.of(
                StageNode.builder("requirements", new RecordingExecutor("requirements", new ArrayList<>()))
                        .build(),
                StageNode.builder("design", new RecordingExecutor("design", new ArrayList<>()))
                        .dependsOn("requirements")
                        .build(),
                StageNode.builder("implementation", new RecordingExecutor("implementation", new ArrayList<>()))
                        .dependsOn("design")
                        .build()));
    }

    private static ContextEntry entry(String stageId, int version, Map<String, Integer> inputVersions) {
        return new ContextEntry(stageId, version, Map.of(), "x", "", "t",
                inputVersions != null ? inputVersions : Map.of());
    }

    private static RunState stateWith(RunContext context, Map<String, StageStatus> nodeStatus) {
        RunState state = new RunState();
        state.runId = "run-1";
        state.workflowName = "replan";
        state.context = context;
        state.nodeStatus = new LinkedHashMap<>(nodeStatus);
        return state;
    }

    @Test
    void staleDownstreamEmptyWhenVersionsMatch() {
        RunContext ctx = new RunContext("run-1");
        ctx.append(entry("requirements", 1, null));
        ctx.append(entry("design", 1, Map.of("requirements", 1)));
        RunState state = stateWith(ctx, Map.of(
                "requirements", StageStatus.PASSED,
                "design", StageStatus.PASSED,
                "implementation", StageStatus.PENDING));
        assertEquals(Set.of(), Replan.staleDownstream(workflow(), state));
    }

    @Test
    void staleDownstreamDetectsVersionDriftAndPropagates() {
        RunContext ctx = new RunContext("run-1");
        ctx.append(entry("requirements", 1, null));
        ctx.append(entry("design", 1, Map.of("requirements", 1)));
        ctx.append(entry("implementation", 1, Map.of("design", 1)));
        ctx.append(entry("requirements", 2, null));
        RunState state = stateWith(ctx, Map.of(
                "requirements", StageStatus.PASSED,
                "design", StageStatus.PASSED,
                "implementation", StageStatus.PASSED));
        assertEquals(Set.of("design", "implementation"), Replan.staleDownstream(workflow(), state));
    }

    @Test
    void invalidateStaleRequeuesMatchingNodes() {
        RunContext ctx = new RunContext("run-1");
        ctx.append(entry("requirements", 1, null));
        ctx.append(entry("design", 1, Map.of("requirements", 1)));
        ctx.append(entry("requirements", 2, null));
        RunState state = stateWith(ctx, Map.of(
                "requirements", StageStatus.PASSED,
                "design", StageStatus.PASSED,
                "implementation", StageStatus.PENDING));
        List<String[]> events = new ArrayList<>();
        Replan.Transition fakeTransition = (s, nodeId, from, to, reason) -> {
            s.nodeStatus.put(nodeId, StageStatus.fromValue(to));
            events.add(new String[] {nodeId, from, to});
        };

        Set<String> stale = Replan.invalidateStale(workflow(), state, fakeTransition);

        assertEquals(Set.of("design"), stale);
        assertEquals(StageStatus.PENDING, state.nodeStatus.get("design"));
        assertTrue(events.stream().anyMatch(e ->
                e[0].equals("design") && e[1].equals("passed") && e[2].equals("invalidated")));
    }
}
