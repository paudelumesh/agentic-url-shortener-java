package com.agentic.orchestrator;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.agentic.orchestrator.support.FlakyExecutor;
import com.agentic.orchestrator.support.RecordingExecutor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RetryFallbackTest {

    @TempDir
    Path tempDir;

    private Engine engine(Workflow workflow) {
        return new Engine(workflow, new RunStore(tempDir));
    }

    static class RaisingExecutor extends StageExecutor {
        RaisingExecutor() {
            name = "raising";
        }

        @Override
        public StageResult run(RunContext context) {
            throw new RuntimeException("boom");
        }
    }

    @Test
    void retrySucceedsWithinMaxAttempts() {
        List<String> calls = new ArrayList<>();
        Workflow wf = new Workflow("retry", List.of(
                StageNode.builder("a", new FlakyExecutor(2, calls))
                        .retryPolicy(new RetryPolicy(3, 0))
                        .build()));
        RunState state = engine(wf).start("run-1");
        assertEquals(StageStatus.PASSED, state.nodeStatus.get("a"));
        assertEquals(3, calls.size());
    }

    @Test
    void retryExhaustedWithoutFallbackFails() {
        List<String> calls = new ArrayList<>();
        Workflow wf = new Workflow("retry", List.of(
                StageNode.builder("a", new FlakyExecutor(5, calls))
                        .retryPolicy(new RetryPolicy(2, 0))
                        .build()));
        RunState state = engine(wf).start("run-1");
        assertEquals(StageStatus.FAILED, state.nodeStatus.get("a"));
        assertEquals(2, calls.size());
    }

    @Test
    void fallbackExecutorRunsAfterRetriesExhausted() {
        List<String> calls = new ArrayList<>();
        List<String> fallbackCalls = new ArrayList<>();
        Workflow wf = new Workflow("retry", List.of(
                StageNode.builder("a", new FlakyExecutor(5, calls))
                        .retryPolicy(new RetryPolicy(1, 0))
                        .fallbackExecutor(new RecordingExecutor("fallback", fallbackCalls))
                        .build()));
        RunState state = engine(wf).start("run-1");
        assertEquals(StageStatus.PASSED, state.nodeStatus.get("a"));
        assertEquals(List.of("fallback"), fallbackCalls);
        assertEquals("RecordingExecutor", state.context.latest("a").producedBy);
    }

    @Test
    void executorRaisingDoesNotCrashRunAndEndsFailed() {
        Workflow wf = new Workflow("raising", List.of(
                StageNode.builder("a", new RaisingExecutor())
                        .retryPolicy(new RetryPolicy(1, 0))
                        .build()));
        RunState state = engine(wf).start("run-1");
        assertEquals(StageStatus.FAILED, state.nodeStatus.get("a"));
    }

    @Test
    void executorRaisingOnFirstAttemptRecoversOnRetry() {
        List<Integer> calls = new ArrayList<>();

        class RaisesThenPasses extends StageExecutor {
            RaisesThenPasses() {
                name = "raises_then_passes";
            }

            @Override
            public StageResult run(RunContext context) {
                calls.add(1);
                if (calls.size() < 2) {
                    throw new RuntimeException("transient boom");
                }
                return new StageResult(StageStatus.PASSED, "");
            }
        }

        Workflow wf = new Workflow("raising", List.of(
                StageNode.builder("a", new RaisesThenPasses())
                        .retryPolicy(new RetryPolicy(3, 0))
                        .build()));
        RunState state = engine(wf).start("run-1");
        assertEquals(StageStatus.PASSED, state.nodeStatus.get("a"));
        assertEquals(2, calls.size());
    }

    @Test
    void executorRaisingRecoversViaFallback() {
        List<String> fallbackCalls = new ArrayList<>();
        Workflow wf = new Workflow("raising", List.of(
                StageNode.builder("a", new RaisingExecutor())
                        .retryPolicy(new RetryPolicy(1, 0))
                        .fallbackExecutor(new RecordingExecutor("fallback", fallbackCalls))
                        .build()));
        RunState state = engine(wf).start("run-1");
        assertEquals(StageStatus.PASSED, state.nodeStatus.get("a"));
        assertEquals(List.of("fallback"), fallbackCalls);
    }

    @Test
    void entryGateRaisingBlocksNodeFailClosed() {
        Workflow wf = new Workflow("raising", List.of(
                StageNode.builder("a", new RecordingExecutor("a", new ArrayList<>()))
                        .entryGate(ctx -> {
                            throw new RuntimeException("entry gate exploded");
                        })
                        .build()));
        RunState state = engine(wf).start("run-1");
        assertEquals(StageStatus.BLOCKED, state.nodeStatus.get("a"));
    }

    @Test
    void exitGateRaisingFailsNodeFailClosed() {
        Workflow wf = new Workflow("raising", List.of(
                StageNode.builder("a", new RecordingExecutor("a", new ArrayList<>()))
                        .exitGate((result, ctx) -> {
                            throw new RuntimeException("exit gate exploded");
                        })
                        .build()));
        RunState state = engine(wf).start("run-1");
        assertEquals(StageStatus.FAILED, state.nodeStatus.get("a"));
    }
}
