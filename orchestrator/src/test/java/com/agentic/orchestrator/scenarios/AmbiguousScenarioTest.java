package com.agentic.orchestrator.scenarios;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import com.agentic.orchestrator.Engine;
import com.agentic.orchestrator.RunState;
import com.agentic.orchestrator.RunStore;
import com.agentic.orchestrator.StageStatus;
import com.agentic.orchestrator.Workflow;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AmbiguousScenarioTest {

    @TempDir
    Path tmp;

    private Path copySource() throws Exception {
        Path repoRoot = Paths.get(System.getProperty("user.dir")).getParent();
        return ScenarioCommon.copyUrlShortenerSource(repoRoot, tmp.resolve("target"));
    }

    @Test
    void requirementsBlocksUntilApproved() throws Exception {
        Path target = copySource();
        Workflow workflow = AmbiguousScenario.build(target);
        Engine engine = new Engine(workflow, new RunStore(target.resolve("runs")));
        RunState state = engine.start("ambiguous-test");

        assertEquals(StageStatus.AWAITING_APPROVAL, state.nodeStatus.get("requirements"));
        assertEquals(StageStatus.PENDING, state.nodeStatus.get("design"));
        List<?> subReqs = (List<?>) state.context.latest("requirements").outputs.get("sub_requirements");
        assertEquals(3, subReqs.size());
    }

    @Test
    void approvingRequirementsRunsFullWorkflowToReleaseGate() throws Exception {
        Path target = copySource();
        Workflow workflow = AmbiguousScenario.build(target);
        Engine engine = new Engine(workflow, new RunStore(target.resolve("runs")));
        engine.start("ambiguous-test-2");
        RunState state = engine.approve("ambiguous-test-2", "requirements", "scope approved");

        assertEquals(StageStatus.PASSED, state.nodeStatus.get("design"));
        assertEquals(StageStatus.PASSED, state.nodeStatus.get("implementation"));
        assertEquals(StageStatus.PASSED, state.nodeStatus.get("unit_tests"));
        assertEquals(StageStatus.AWAITING_APPROVAL, state.nodeStatus.get("release_readiness"));
    }
}
