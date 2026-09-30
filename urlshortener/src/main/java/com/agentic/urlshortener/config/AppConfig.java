package com.agentic.urlshortener.config;

public final class AppConfig {
    public static final int CODE_LENGTH = 7;
    public static final int RATE_LIMIT_CAPACITY = 60;
    public static final double RATE_LIMIT_REFILL_PER_SECOND = 1.0;
    public static final String BASE_URL = "http://localhost:8000";

    private AppConfig() {
    }
}
