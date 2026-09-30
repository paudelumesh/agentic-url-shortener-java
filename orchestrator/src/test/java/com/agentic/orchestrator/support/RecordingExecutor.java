package com.agentic.orchestrator.support;

import java.util.List;
import java.util.Map;

import com.agentic.orchestrator.RunContext;
import com.agentic.orchestrator.StageExecutor;
import com.agentic.orchestrator.StageResult;
import com.agentic.orchestrator.StageStatus;

public class RecordingExecutor extends StageExecutor {

    private final List<String> calls;
    private final Map<String, Object> outputs;
    private final StageStatus status;
    private final String notes;

    public RecordingExecutor(String name, List<String> calls) {
        this(name, calls, Map.of(), StageStatus.PASSED, "");
    }

    public RecordingExecutor(String name, List<String> calls, Map<String, Object> outputs) {
        this(name, calls, outputs, StageStatus.PASSED, "");
    }

    public RecordingExecutor(String name, List<String> calls, Map<String, Object> outputs, StageStatus status) {
        this(name, calls, outputs, status, "");
    }

    public RecordingExecutor(String name, List<String> calls, Map<String, Object> outputs, StageStatus status,
            String notes) {
        this.name = name;
        this.calls = calls;
        this.outputs = outputs;
        this.status = status;
        this.notes = notes;
    }

    @Override
    public StageResult run(RunContext context) {
        calls.add(name);
        return new StageResult(status, outputs, notes);
    }
}
