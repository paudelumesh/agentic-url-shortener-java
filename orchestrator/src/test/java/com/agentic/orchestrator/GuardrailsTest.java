package com.agentic.orchestrator;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class GuardrailsTest {

    private static StageResult result(Map<String, Object> outputs) {
        return new StageResult(StageStatus.PASSED, outputs, "");
    }

    @Test
    void noSecretsInDiffPassesCleanDiff() {
        GateResult outcome = Guardrails.noSecretsInDiff()
                .apply(result(Map.of("diff_preview", "def foo(): pass")), null);
        assertEquals("pass", outcome.outcome);
    }

    @Test
    void noSecretsInDiffFailsOnDetectedMarker() {
        GateResult outcome = Guardrails.noSecretsInDiff()
                .apply(result(Map.of("diff_preview", "aws_secret_access_key = 'x'")), null);
        assertEquals("fail", outcome.outcome);
    }

    @Test
    void destructiveMigrationRequiresApproval() {
        GateResult outcome = Guardrails.destructiveMigrationRequiresApproval()
                .apply(result(Map.of("schema_change", "destructive")), null);
        assertEquals("needs_approval", outcome.outcome);
    }

    @Test
    void additiveMigrationPasses() {
        GateResult outcome = Guardrails.destructiveMigrationRequiresApproval()
                .apply(result(Map.of("schema_change", "additive")), null);
        assertEquals("pass", outcome.outcome);
    }

    @Test
    void newDependencyRequiresApproval() {
        GateResult outcome = Guardrails.newDependencyRequiresApproval()
                .apply(result(Map.of("new_dependencies", List.of("requests"))), null);
        assertEquals("needs_approval", outcome.outcome);
    }

    @Test
    void coverageThresholdFailsBelowMinimum() {
        var rule = Guardrails.testCoverageThreshold(1.0);
        GateResult outcome = rule.apply(result(Map.of("tests_total", 10, "tests_passed", 8)), null);
        assertEquals("fail", outcome.outcome);
    }

    @Test
    void coverageThresholdPassesAtMinimum() {
        var rule = Guardrails.testCoverageThreshold(1.0);
        GateResult outcome = rule.apply(result(Map.of("tests_total", 10, "tests_passed", 10)), null);
        assertEquals("pass", outcome.outcome);
    }

    @Test
    void combineGuardrailsReturnsFirstNonPass() {
        var combined = Policy.combineGuardrails(
                Guardrails.noSecretsInDiff(), Guardrails.testCoverageThreshold(1.0));
        GateResult outcome = combined.apply(result(Map.of(
                "diff_preview", "aws_secret_access_key=x", "tests_total", 1, "tests_passed", 1)), null);
        assertEquals("fail", outcome.outcome);
    }

    @Test
    void combineGuardrailsPassesWhenAllPass() {
        var combined = Policy.combineGuardrails(
                Guardrails.noSecretsInDiff(), Guardrails.testCoverageThreshold(1.0));
        GateResult outcome = combined.apply(result(Map.of(
                "diff_preview", "clean", "tests_total", 1, "tests_passed", 1)), null);
        assertEquals("pass", outcome.outcome);
    }

    @Test
    void combineGuardrailsFailsWhenStageResultItselfFailed() {
        StageResult failedResult = new StageResult(StageStatus.FAILED, "executor blew up");
        GateResult outcome = Policy.combineGuardrails(Guardrails.noSecretsInDiff()).apply(failedResult, null);
        assertEquals("fail", outcome.outcome);
        assertEquals("executor blew up", outcome.reason);
    }
}
