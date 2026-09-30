package com.agentic.orchestrator;

public class CycleException extends RuntimeException {
    public CycleException(String message) {
        super(message);
    }
}
