package com.agentic.orchestrator;

import java.util.LinkedHashMap;
import java.util.Map;

public class ContextEntry {
    public String stageId;
    public int version;
    public Map<String, Object> outputs = new LinkedHashMap<>();
    public String producedBy = "";
    public String rationale = "";
    public String timestamp = "";
    public Map<String, Integer> inputVersions = new LinkedHashMap<>();

    public ContextEntry() {}

    public ContextEntry(String stageId, int version, Map<String, Object> outputs, String producedBy,
                        String rationale, String timestamp, Map<String, Integer> inputVersions) {
        this.stageId = stageId;
        this.version = version;
        this.outputs = outputs != null ? outputs : new LinkedHashMap<>();
        this.producedBy = producedBy != null ? producedBy : "";
        this.rationale = rationale != null ? rationale : "";
        this.timestamp = timestamp != null ? timestamp : "";
        this.inputVersions = inputVersions != null ? inputVersions : new LinkedHashMap<>();
    }
}
