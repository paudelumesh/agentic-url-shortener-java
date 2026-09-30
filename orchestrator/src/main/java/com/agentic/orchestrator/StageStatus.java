package com.agentic.orchestrator;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum StageStatus {
    PENDING("pending"),
    RUNNING("running"),
    PASSED("passed"),
    FAILED("failed"),
    AWAITING_APPROVAL("awaiting_approval"),
    BLOCKED("blocked"),
    INVALIDATED("invalidated"),
    ROLLED_BACK("rolled_back"),
    STOPPED("stopped");

    private final String value;

    StageStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static StageStatus fromValue(String value) {
        for (StageStatus s : values()) {
            if (s.value.equals(value)) {
                return s;
            }
        }
        throw new IllegalArgumentException("Unknown StageStatus: " + value);
    }
}
