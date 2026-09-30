package com.agentic.orchestrator;

import java.util.List;
import java.util.function.BiFunction;

public final class Guardrails {
    private Guardrails() {}

    private static final List<String> BANNED_SECRET_MARKERS = List.of(
            "-----BEGIN PRIVATE KEY", "aws_secret_access_key", "api_key=sk-");

    public static BiFunction<StageResult, RunContext, GateResult> noSecretsInDiff() {
        return (result, context) -> {
            Object diff = result.outputs.get("diff_preview");
            String diffText = diff == null ? "" : String.valueOf(diff).toLowerCase();
            for (String marker : BANNED_SECRET_MARKERS) {
                if (diffText.contains(marker.toLowerCase())) {
                    return GateResult.fail("potential secret detected: " + marker);
                }
            }
            return GateResult.pass();
        };
    }

    public static BiFunction<StageResult, RunContext, GateResult> destructiveMigrationRequiresApproval() {
        return (result, context) -> {
            if ("destructive".equals(result.outputs.get("schema_change"))) {
                return GateResult.needsApproval("destructive schema change requires human approval");
            }
            return GateResult.pass();
        };
    }

    public static BiFunction<StageResult, RunContext, GateResult> newDependencyRequiresApproval() {
        return (result, context) -> {
            Object newDeps = result.outputs.get("new_dependencies");
            if (newDeps != null) {
                return GateResult.needsApproval("new dependencies added: " + newDeps);
            }
            return GateResult.pass();
        };
    }

    public static BiFunction<StageResult, RunContext, GateResult> testCoverageThreshold(double minPassRate) {
        return (result, context) -> {
            Object totalObj = result.outputs.getOrDefault("tests_total", 0);
            Object passedObj = result.outputs.getOrDefault("tests_passed", 0);
            double total = ((Number) totalObj).doubleValue();
            double passed = ((Number) passedObj).doubleValue();
            if (total == 0) {
                return GateResult.pass();
            }
            double rate = passed / total;
            if (rate < minPassRate) {
                return GateResult.fail(String.format("test pass rate %.0f%% below required %.0f%%",
                        rate * 100, minPassRate * 100));
            }
            return GateResult.pass();
        };
    }
}
