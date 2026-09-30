package com.agentic.orchestrator;

import java.util.function.BiFunction;

public final class Policy {
    private Policy() {}

    @SafeVarargs
    public static BiFunction<StageResult, RunContext, GateResult> combineGuardrails(
            BiFunction<StageResult, RunContext, GateResult>... rules) {
        return (result, context) -> {
            if (result.status != StageStatus.PASSED) {
                return GateResult.fail(result.notes);
            }
            for (var rule : rules) {
                GateResult outcome = rule.apply(result, context);
                if (!"pass".equals(outcome.outcome)) {
                    return outcome;
                }
            }
            return GateResult.pass();
        };
    }
}
