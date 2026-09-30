package com.agentic.orchestrator;

public class Event {
    public String runId;
    public String nodeId;
    public String fromState;
    public String toState;
    public String timestamp;
    public int attempt = 1;
    public String reason = "";

    public Event() {}

    public Event(String runId, String nodeId, String fromState, String toState, String timestamp, int attempt, String reason) {
        this.runId = runId;
        this.nodeId = nodeId;
        this.fromState = fromState;
        this.toState = toState;
        this.timestamp = timestamp;
        this.attempt = attempt;
        this.reason = reason != null ? reason : "";
    }
}
