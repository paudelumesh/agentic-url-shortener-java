package com.agentic.orchestrator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.agentic.orchestrator.support.RecordingExecutor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SafeStopTest {

    @TempDir
    Path tempDir;

    @Test
    void stopHaltsBeforePendingNodesRunAndResumeContinues() {
        List<String> calls = new ArrayList<>();
        Workflow wf = new Workflow("stoppable", List.of(
                StageNode.builder("a", new RecordingExecutor("a", calls))
                        .requiresApproval(true)
                        .build(),
                StageNode.builder("b", new RecordingExecutor("b", calls))
                        .dependsOn("a")
                        .build()));
        Engine engine = new Engine(wf, new RunStore(tempDir));
        engine.start("run-1");
        engine.stop("run-1");
        RunState state = engine.approve("run-1", "a", "ok");
        assertEquals(StageStatus.STOPPED, state.nodeStatus.get("b"));
        assertEquals(List.of("a"), calls);

        RunState resumed = engine.resume("run-1");
        assertEquals(StageStatus.PASSED, resumed.nodeStatus.get("b"));
        assertEquals(List.of("a", "b"), calls);
        assertFalse(resumed.safeStopRequested);
    }
}
