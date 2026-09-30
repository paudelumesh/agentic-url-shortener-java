package com.agentic.orchestrator;

import java.util.LinkedHashMap;
import java.util.Map;

public class RunState {
    public String runId;
    public String workflowName;
    public Map<String, StageStatus> nodeStatus = new LinkedHashMap<>();
    public Map<String, Integer> nodeAttempts = new LinkedHashMap<>();
    public RunContext context;
    public boolean safeStopRequested;
    public String startedAt = "";
    public String finishedAt = "";

    public RunState() {}
}
