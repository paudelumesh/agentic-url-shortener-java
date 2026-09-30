package com.agentic.orchestrator.support;

import java.util.List;

import com.agentic.orchestrator.RunContext;
import com.agentic.orchestrator.StageExecutor;
import com.agentic.orchestrator.StageResult;
import com.agentic.orchestrator.StageStatus;

public class FlakyExecutor extends StageExecutor {

    private final int failTimes;
    private final List<String> calls;
    private int attempts;

    public FlakyExecutor(int failTimes, List<String> calls) {
        this(failTimes, calls, "flaky");
    }

    public FlakyExecutor(int failTimes, List<String> calls, String name) {
        this.name = name;
        this.failTimes = failTimes;
        this.calls = calls;
    }

    @Override
    public StageResult run(RunContext context) {
        attempts++;
        calls.add(name);
        if (attempts <= failTimes) {
            return new StageResult(StageStatus.FAILED, "attempt " + attempts + " failed");
        }
        return new StageResult(StageStatus.PASSED, "");
    }
}
