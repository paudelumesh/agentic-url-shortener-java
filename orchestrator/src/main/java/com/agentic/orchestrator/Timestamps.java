package com.agentic.orchestrator;

import java.time.Instant;

public final class Timestamps {
    private Timestamps() {}

    public static String nowIso() {
        return Instant.now().toString();
    }
}
