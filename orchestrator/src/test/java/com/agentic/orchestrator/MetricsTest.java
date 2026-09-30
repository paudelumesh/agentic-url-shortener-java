package com.agentic.orchestrator;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class MetricsTest {

    private static Event event(String runId, String nodeId, String from, String to, String timestamp) {
        return new Event(runId, nodeId, from, to, timestamp, 1, "");
    }

    private static Event event(String runId, String nodeId, String from, String to, String timestamp, int attempt) {
        return new Event(runId, nodeId, from, to, timestamp, attempt, "");
    }

    @Test
    void computeRunMetricsBasicSuccessRate() {
        List<Event> events = List.of(
                event("r1", "a", "pending", "running", "2026-01-01T00:00:00+00:00"),
                event("r1", "a", "running", "passed", "2026-01-01T00:00:01+00:00"),
                event("r1", "b", "pending", "running", "2026-01-01T00:00:01+00:00"),
                event("r1", "b", "running", "passed", "2026-01-01T00:00:02+00:00"));
        Metrics.RunMetrics metrics = Metrics.computeRunMetrics("r1", events);
        assertEquals(2, metrics.totalNodes());
        assertEquals(2, metrics.passedNodes());
        assertEquals(1.0, metrics.successRate(), 1e-9);
        assertEquals(2.0, metrics.latencySeconds(), 1e-9);
    }

    @Test
    void computeRunMetricsTracksRetryAndRollback() {
        List<Event> events = List.of(
                event("r1", "a", "pending", "running", "2026-01-01T00:00:00+00:00", 1),
                event("r1", "a", "running", "failed", "2026-01-01T00:00:01+00:00", 1),
                event("r1", "a", "running", "rolled_back", "2026-01-01T00:00:02+00:00", 2));
        Metrics.RunMetrics metrics = Metrics.computeRunMetrics("r1", events);
        assertEquals(1, metrics.retriedNodes());
        assertEquals(1, metrics.rolledBackNodes());
    }

    @Test
    void computeRunMetricsMttrFromFailureToRecovery() {
        List<Event> events = List.of(
                event("r1", "a", "running", "failed", "2026-01-01T00:00:00+00:00"),
                event("r1", "a", "running", "passed", "2026-01-01T00:00:05+00:00"));
        Metrics.RunMetrics metrics = Metrics.computeRunMetrics("r1", events);
        assertEquals(5.0, metrics.mttrSeconds(), 1e-9);
    }

    @Test
    void aggregateMetricsAveragesAcrossRuns() {
        List<Event> eventsR1 = List.of(
                event("r1", "a", "running", "passed", "2026-01-01T00:00:00+00:00"));
        List<Event> eventsR2 = List.of(
                event("r2", "a", "running", "failed", "2026-01-01T00:00:00+00:00"));
        Map<String, Object> report = Metrics.aggregateMetrics(Map.of("r1", eventsR1, "r2", eventsR2));
        assertEquals(2, report.get("runs"));
        assertEquals(0.5, (double) report.get("avg_success_rate"), 1e-9);
    }
}
