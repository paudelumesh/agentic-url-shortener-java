package com.agentic.orchestrator;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.agentic.orchestrator.support.RecordingExecutor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RollbackTest {

    @TempDir
    Path tempDir;

    private Engine engine(Workflow workflow) {
        return new Engine(workflow, new RunStore(tempDir));
    }

    private static StageNode node(String id, StageExecutor executor, String... deps) {
        return StageNode.builder(id, executor).dependsOn(deps).build();
    }

    @Test
    void rollbackHookRunsOnFinalFailure() {
        List<String> rollbackCalls = new ArrayList<>();
        Workflow wf = new Workflow("rollback", List.of(
                StageNode.builder("a",
                                new RecordingExecutor("a", new ArrayList<>(), Map.of(), StageStatus.FAILED, "broke"))
                        .rollback(ctx -> rollbackCalls.add("rolled_back"))
                        .build()));
        RunState state = engine(wf).start("run-1");
        assertEquals(StageStatus.ROLLED_BACK, state.nodeStatus.get("a"));
        assertEquals(List.of("rolled_back"), rollbackCalls);
    }

    @Test
    void failedNodeBlocksDownstream() {
        Workflow wf = new Workflow("rollback", List.of(
                node("a", new RecordingExecutor("a", new ArrayList<>(), Map.of(), StageStatus.FAILED, "broke")),
                node("b", new RecordingExecutor("b", new ArrayList<>()), "a")));
        RunState state = engine(wf).start("run-1");
        assertEquals(StageStatus.FAILED, state.nodeStatus.get("a"));
        assertEquals(StageStatus.BLOCKED, state.nodeStatus.get("b"));
    }
}
