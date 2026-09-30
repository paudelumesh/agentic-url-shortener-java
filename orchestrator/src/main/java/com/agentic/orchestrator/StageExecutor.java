package com.agentic.orchestrator;

public abstract class StageExecutor {
    public String name = "executor";

    public abstract StageResult run(RunContext context);
}
