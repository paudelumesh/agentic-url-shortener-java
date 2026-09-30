package com.agentic.orchestrator;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Metrics {
    private Metrics() {}

    public record RunMetrics(
            String runId,
            int totalNodes,
            int passedNodes,
            int retriedNodes,
            int rolledBackNodes,
            double successRate,
            double retryFrequency,
            double rollbackFrequency,
            Double mttrSeconds,
            Double latencySeconds) {}

    public static RunMetrics computeRunMetrics(String runId, List<Event> events) {
        var nodeIds = new java.util.HashSet<String>();
        for (Event e : events) {
            nodeIds.add(e.nodeId);
        }
        int total = nodeIds.size();
        var passed = new java.util.HashSet<String>();
        var retried = new java.util.HashSet<String>();
        var rolledBack = new java.util.HashSet<String>();
        for (Event e : events) {
            if ("passed".equals(e.toState)) {
                passed.add(e.nodeId);
            }
            if (e.attempt > 1) {
                retried.add(e.nodeId);
            }
            if ("rolled_back".equals(e.toState)) {
                rolledBack.add(e.nodeId);
            }
        }

        List<Double> recoveryTimes = new ArrayList<>();
        Map<String, Instant> openFailures = new HashMap<>();
        List<Event> ordered = new ArrayList<>(events);
        ordered.sort(Comparator.comparing(e -> Instant.parse(e.timestamp)));
        for (Event e : ordered) {
            Instant ts = Instant.parse(e.timestamp);
            if ("failed".equals(e.toState)) {
                openFailures.put(e.nodeId, ts);
            } else if (("passed".equals(e.toState) || "rolled_back".equals(e.toState)) && openFailures.containsKey(e.nodeId)) {
                recoveryTimes.add((double) Duration.between(openFailures.remove(e.nodeId), ts).toMillis() / 1000.0);
            }
        }

        Double latency = null;
        if (!events.isEmpty()) {
            Instant min = null, max = null;
            for (Event e : events) {
                Instant ts = Instant.parse(e.timestamp);
                if (min == null || ts.isBefore(min)) {
                    min = ts;
                }
                if (max == null || ts.isAfter(max)) {
                    max = ts;
                }
            }
            latency = (double) Duration.between(min, max).toMillis() / 1000.0;
        }

        return new RunMetrics(
                runId, total, passed.size(), retried.size(), rolledBack.size(),
                total > 0 ? (double) passed.size() / total : 0.0,
                total > 0 ? (double) retried.size() / total : 0.0,
                total > 0 ? (double) rolledBack.size() / total : 0.0,
                recoveryTimes.isEmpty() ? null : recoveryTimes.stream().mapToDouble(d -> d).average().orElse(0),
                latency);
    }

    public static Map<String, Object> aggregateMetrics(Map<String, List<Event>> allEvents) {
        Map<String, RunMetrics> perRun = new LinkedHashMap<>();
        for (var entry : allEvents.entrySet()) {
            perRun.put(entry.getKey(), computeRunMetrics(entry.getKey(), entry.getValue()));
        }
        double count = perRun.isEmpty() ? 1 : perRun.size();
        Map<String, Object> perRunMaps = new LinkedHashMap<>();
        for (var entry : perRun.entrySet()) {
            perRunMaps.put(entry.getKey(), Json.MAPPER.convertValue(entry.getValue(), Map.class));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("runs", perRun.size());
        out.put("avg_success_rate", perRun.values().stream().mapToDouble(RunMetrics::successRate).sum() / count);
        out.put("avg_retry_frequency", perRun.values().stream().mapToDouble(RunMetrics::retryFrequency).sum() / count);
        out.put("avg_rollback_frequency", perRun.values().stream().mapToDouble(RunMetrics::rollbackFrequency).sum() / count);
        out.put("per_run", perRunMaps);
        return out;
    }
}
