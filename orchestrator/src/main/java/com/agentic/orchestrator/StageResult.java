package com.agentic.orchestrator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class StageResult {
    public final StageStatus status;
    public final Map<String, Object> outputs;
    public final List<String> artifacts;
    public final String notes;
    public final List<String> risks;

    public StageResult(StageStatus status, Map<String, Object> outputs, List<String> artifacts, String notes, List<String> risks) {
        this.status = status;
        this.outputs = outputs != null ? outputs : new LinkedHashMap<>();
        this.artifacts = artifacts != null ? artifacts : new ArrayList<>();
        this.notes = notes != null ? notes : "";
        this.risks = risks != null ? risks : new ArrayList<>();
    }

    public StageResult(StageStatus status, String notes) {
        this(status, new LinkedHashMap<>(), new ArrayList<>(), notes, new ArrayList<>());
    }

    public StageResult(StageStatus status, Map<String, Object> outputs, String notes) {
        this(status, outputs, new ArrayList<>(), notes, new ArrayList<>());
    }
}
