package com.agentic.orchestrator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class GraphTest {

    static class NoopExecutor extends StageExecutor {
        @Override
        public StageResult run(RunContext context) {
            return new StageResult(StageStatus.PASSED, "");
        }
    }

    private static StageNode node(String id, String... deps) {
        return StageNode.builder(id, new NoopExecutor()).dependsOn(deps).build();
    }

    @Test
    void workflowAcceptsValidDag() {
        Workflow wf = new Workflow("demo", List.of(node("a"), node("b", "a"), node("c", "a", "b")));
        assertEquals(Set.of("a", "b", "c"), wf.nodes.keySet());
    }

    @Test
    void workflowRejectsUnknownDependency() {
        assertThrows(IllegalArgumentException.class,
                () -> new Workflow("demo", List.of(node("a", "missing"))));
    }

    @Test
    void workflowRejectsCycles() {
        assertThrows(CycleException.class,
                () -> new Workflow("demo", List.of(node("a", "b"), node("b", "a"))));
    }

    @Test
    void dependentsOfReturnsDirectChildren() {
        Workflow wf = new Workflow("demo", List.of(node("a"), node("b", "a"), node("c", "a")));
        assertEquals(Set.of("b", "c"), new HashSet<>(wf.dependentsOf("a")));
    }

    @Test
    void allDownstreamReturnsTransitiveChildren() {
        Workflow wf = new Workflow("demo", List.of(node("a"), node("b", "a"), node("c", "b")));
        assertEquals(Set.of("b", "c"), wf.allDownstream("a"));
    }

    @Test
    void defaultEntryGateIsOk() {
        Workflow wf = new Workflow("demo", List.of(node("a")));
        assertEquals("ok", wf.nodes.get("a").entryGate.apply(null).outcome);
    }

    @Test
    void defaultExitGatePassesOnPassedStatus() {
        Workflow wf = new Workflow("demo", List.of(node("a")));
        GateResult result = wf.nodes.get("a").exitGate.apply(new StageResult(StageStatus.PASSED, ""), null);
        assertEquals("pass", result.outcome);
    }

    @Test
    void defaultExitGateFailsOnFailedStatus() {
        Workflow wf = new Workflow("demo", List.of(node("a")));
        GateResult result = wf.nodes.get("a").exitGate
                .apply(new StageResult(StageStatus.FAILED, "boom"), null);
        assertEquals("fail", result.outcome);
        assertEquals("boom", result.reason);
    }
}
