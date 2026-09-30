package com.agentic.orchestrator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.agentic.orchestrator.support.RecordingExecutor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ApprovalTest {

    @TempDir
    Path tempDir;

    private Engine engine(Workflow workflow) {
        return new Engine(workflow, new RunStore(tempDir));
    }

    private static StageNode node(String id, StageExecutor executor, String... deps) {
        return StageNode.builder(id, executor).dependsOn(deps).build();
    }

    private static Workflow gatedWorkflow() {
        return new Workflow("gated", List.of(
                StageNode.builder("a", new RecordingExecutor("a", new ArrayList<>()))
                        .requiresApproval(true)
                        .build(),
                node("b", new RecordingExecutor("b", new ArrayList<>()), "a")));
    }

    @Test
    void nodeWithRequiresApprovalPausesRun() {
        RunState state = engine(gatedWorkflow()).start("run-1");
        assertEquals(StageStatus.AWAITING_APPROVAL, state.nodeStatus.get("a"));
        assertEquals(StageStatus.PENDING, state.nodeStatus.get("b"));
    }

    @Test
    void approveResumesDownstreamNodes() {
        Engine eng = engine(gatedWorkflow());
        eng.start("run-1");
        RunState state = eng.approve("run-1", "a", "looks good");
        assertEquals(StageStatus.PASSED, state.nodeStatus.get("a"));
        assertEquals(StageStatus.PASSED, state.nodeStatus.get("b"));
    }

    @Test
    void approveRaisesIfNodeNotAwaitingApproval() {
        Workflow wf = new Workflow("gated", List.of(
                node("a", new RecordingExecutor("a", new ArrayList<>()))));
        Engine eng = engine(wf);
        eng.start("run-1");
        assertThrows(IllegalArgumentException.class, () -> eng.approve("run-1", "a", "x"));
    }

    @Test
    void rejectRerunsReviseStageThenRegatesRejectedNode() {
        Workflow wf = new Workflow("gated", List.of(
                node("requirements", new RecordingExecutor("requirements", new ArrayList<>())),
                StageNode.builder("design", new RecordingExecutor("design", new ArrayList<>()))
                        .dependsOn("requirements")
                        .requiresApproval(true)
                        .build()));
        Engine eng = engine(wf);
        eng.start("run-1");
        RunState state = eng.reject("run-1", "design", "needs rework", "requirements");
        assertEquals(StageStatus.PASSED, state.nodeStatus.get("requirements"));
        assertEquals(StageStatus.AWAITING_APPROVAL, state.nodeStatus.get("design"));
        assertEquals(2, state.context.latest("requirements").version);
    }

    @Test
    void rejectSelfReviseRerunsSameNode() {
        Workflow wf = new Workflow("gated", List.of(
                StageNode.builder("requirements", new RecordingExecutor("requirements", new ArrayList<>()))
                        .requiresApproval(true)
                        .build()));
        Engine eng = engine(wf);
        eng.start("run-1");
        RunState state = eng.reject("run-1", "requirements", "too vague", "requirements");
        assertEquals(StageStatus.AWAITING_APPROVAL, state.nodeStatus.get("requirements"));
    }
}
