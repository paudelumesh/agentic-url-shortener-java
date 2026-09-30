package com.agentic.orchestrator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import java.util.NoSuchElementException;

import org.junit.jupiter.api.Test;

class ContextTest {

    private static ContextEntry entry(String stageId, int version, Map<String, Object> outputs,
            Map<String, Integer> inputVersions) {
        return new ContextEntry(stageId, version, outputs, "TestExecutor", "because",
                "2026-08-16T00:00:00+00:00", inputVersions);
    }

    private static ContextEntry entry() {
        return entry("requirements", 1, Map.of("summary", "ok"), Map.of());
    }

    @Test
    void latestReturnsNullWhenEmpty() {
        assertNull(new RunContext("run-1").latest("requirements"));
    }

    @Test
    void appendAndLatestRoundtrip() {
        RunContext ctx = new RunContext("run-1");
        ctx.append(entry());
        assertEquals(Map.of("summary", "ok"), ctx.latest("requirements").outputs);
    }

    @Test
    void nextVersionIncrements() {
        RunContext ctx = new RunContext("run-1");
        assertEquals(1, ctx.nextVersion("requirements"));
        ctx.append(entry());
        assertEquals(2, ctx.nextVersion("requirements"));
    }

    @Test
    void getVersionRaisesForMissingVersion() {
        RunContext ctx = new RunContext("run-1");
        ctx.append(entry());
        assertThrows(NoSuchElementException.class, () -> ctx.getVersion("requirements", 2));
    }

    @Test
    void toJsonFromJsonRoundtrip() {
        RunContext ctx = new RunContext("run-1");
        ctx.append(entry());
        ctx.append(entry("design", 1, Map.of("summary", "ok"), Map.of("requirements", 1)));
        RunContext restored = Json.readValue(Json.writeValueAsString(ctx), RunContext.class);
        assertEquals("run-1", restored.runId);
        assertEquals(Map.of("summary", "ok"), restored.latest("requirements").outputs);
        assertEquals(Map.of("requirements", 1), restored.latest("design").inputVersions);
    }
}
