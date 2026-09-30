package com.agentic.orchestrator;

public class RetryPolicy {
    public int maxAttempts = 1;
    public double backoffSeconds = 0;

    public RetryPolicy() {}

    public RetryPolicy(int maxAttempts, double backoffSeconds) {
        this.maxAttempts = maxAttempts;
        this.backoffSeconds = backoffSeconds;
    }
}
