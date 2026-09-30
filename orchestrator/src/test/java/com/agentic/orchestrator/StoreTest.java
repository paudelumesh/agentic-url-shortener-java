package com.agentic.orchestrator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StoreTest {

    @TempDir
    Path tmp;

    private static RunState state(String runId) {
        RunState state = new RunState();
        state.runId = runId;
        state.workflowName = "demo";
        state.context = new RunContext(runId);
        return state;
    }

    @Test
    void saveAndLoadRoundtrip() {
        RunStore store = new RunStore(tmp);
        RunState state = state("run-1");
        state.nodeStatus = new LinkedHashMap<>(Map.of("a", StageStatus.PASSED));
        state.nodeAttempts = new LinkedHashMap<>(Map.of("a", 1));
        state.startedAt = "t0";
        store.save(state);

        RunState loaded = store.load("run-1");
        assertEquals(Map.of("a", StageStatus.PASSED), loaded.nodeStatus);
        assertEquals("run-1", loaded.context.runId);
    }

    @Test
    void existsFalseForUnknownRun() {
        assertFalse(new RunStore(tmp).exists("nope"));
    }

    @Test
    void listRunsReturnsSavedRunIds() {
        RunStore store = new RunStore(tmp);
        store.save(state("run-a"));
        store.save(state("run-b"));
        assertEquals(List.of("run-a", "run-b"), store.listRuns());
    }
}
