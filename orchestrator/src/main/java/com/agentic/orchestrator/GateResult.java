package com.agentic.orchestrator;

public final class GateResult {
    public final String outcome;
    public final String reason;

    public GateResult(String outcome, String reason) {
        this.outcome = outcome;
        this.reason = reason != null ? reason : "";
    }

    public GateResult(String outcome) {
        this(outcome, "");
    }

    public static GateResult ok() {
        return new GateResult("ok");
    }

    public static GateResult blocked(String reason) {
        return new GateResult("blocked", reason);
    }

    public static GateResult pass() {
        return new GateResult("pass");
    }

    public static GateResult fail(String reason) {
        return new GateResult("fail", reason);
    }

    public static GateResult needsApproval(String reason) {
        return new GateResult("needs_approval", reason);
    }
}
