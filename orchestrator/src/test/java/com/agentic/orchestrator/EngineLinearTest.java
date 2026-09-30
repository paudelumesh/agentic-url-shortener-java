package com.agentic.orchestrator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.agentic.orchestrator.support.RecordingExecutor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EngineLinearTest {

    @TempDir
    Path tempDir;

    private Engine engine(Workflow workflow) {
        return new Engine(workflow, new RunStore(tempDir));
    }

    private static StageNode node(String id, StageExecutor executor, String... deps) {
        return StageNode.builder(id, executor).dependsOn(deps).build();
    }

    @Test
    void linearWorkflowRunsNodesInDependencyOrder() {
        List<String> calls = new ArrayList<>();
        Workflow wf = new Workflow("linear", List.of(
                node("a", new RecordingExecutor("a", calls)),
                node("b", new RecordingExecutor("b", calls), "a"),
                node("c", new RecordingExecutor("c", calls), "b")));
        RunState state = engine(wf).start("run-1");
        assertEquals(List.of("a", "b", "c"), calls);
        assertEquals(
                Map.of("a", StageStatus.PASSED, "b", StageStatus.PASSED, "c", StageStatus.PASSED),
                state.nodeStatus);
        assertFalse(state.finishedAt.isEmpty());
    }

    @Test
    void parallelBranchesBothRunBeforeSyncNode() {
        List<String> calls = new ArrayList<>();
        Workflow wf = new Workflow("fanout", List.of(
                node("a", new RecordingExecutor("a", calls)),
                node("b1", new RecordingExecutor("b1", calls), "a"),
                node("b2", new RecordingExecutor("b2", calls), "a"),
                node("sync", new RecordingExecutor("sync", calls), "b1", "b2")));
        RunState state = engine(wf).start("run-1");
        assertEquals("a", calls.get(0));
        assertEquals(Set.of("b1", "b2"), new HashSet<>(calls.subList(1, 3)));
        assertEquals("sync", calls.get(3));
        assertEquals(StageStatus.PASSED, state.nodeStatus.get("sync"));
    }

    @Test
    void entryGateBlocksNode() {
        List<String> calls = new ArrayList<>();
        Workflow wf = new Workflow("blocked", List.of(
                StageNode.builder("a", new RecordingExecutor("a", calls))
                        .entryGate(ctx -> GateResult.blocked("not allowed"))
                        .build()));
        RunState state = engine(wf).start("run-1");
        assertTrue(calls.isEmpty());
        assertEquals(StageStatus.BLOCKED, state.nodeStatus.get("a"));
    }

    @Test
    void contextRecordsInputVersions() {
        Workflow wf = new Workflow("linear", List.of(
                node("a", new RecordingExecutor("a", new ArrayList<>(), Map.of("x", 1))),
                node("b", new RecordingExecutor("b", new ArrayList<>(), Map.of("y", 2)), "a")));
        RunState state = engine(wf).start("run-1");
        assertEquals(Map.of("a", 1), state.context.latest("b").inputVersions);
    }
}
