package com.agentic.orchestrator;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

public class RunContext {
    public String runId;
    public Map<String, List<ContextEntry>> history = new LinkedHashMap<>();

    public RunContext() {}

    public RunContext(String runId) {
        this.runId = runId;
    }

    public synchronized void append(ContextEntry entry) {
        history.computeIfAbsent(entry.stageId, k -> new java.util.ArrayList<>()).add(entry);
    }

    public synchronized ContextEntry latest(String stageId) {
        List<ContextEntry> entries = history.get(stageId);
        return (entries == null || entries.isEmpty()) ? null : entries.get(entries.size() - 1);
    }

    public synchronized ContextEntry getVersion(String stageId, int version) {
        List<ContextEntry> entries = history.get(stageId);
        if (entries != null) {
            for (ContextEntry entry : entries) {
                if (entry.version == version) {
                    return entry;
                }
            }
        }
        throw new NoSuchElementException(stageId + " v" + version + " not found");
    }

    public synchronized int nextVersion(String stageId) {
        ContextEntry last = latest(stageId);
        return last == null ? 1 : last.version + 1;
    }
}
