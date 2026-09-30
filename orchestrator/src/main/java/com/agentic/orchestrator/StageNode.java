package com.agentic.orchestrator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

public final class StageNode {
    public final String id;
    public final StageExecutor executor;
    public final List<String> dependsOn;
    public final Function<RunContext, GateResult> entryGate;
    public final BiFunction<StageResult, RunContext, GateResult> exitGate;
    public final RetryPolicy retryPolicy;
    public final StageExecutor fallbackExecutor;
    public final boolean requiresApproval;
    public final Consumer<RunContext> rollback;
    public final Supplier<Object> snapshot;

    private StageNode(Builder b) {
        this.id = b.id;
        this.executor = b.executor;
        this.dependsOn = Collections.unmodifiableList(new ArrayList<>(b.dependsOn));
        this.entryGate = b.entryGate;
        this.exitGate = b.exitGate;
        this.retryPolicy = b.retryPolicy;
        this.fallbackExecutor = b.fallbackExecutor;
        this.requiresApproval = b.requiresApproval;
        this.rollback = b.rollback;
        this.snapshot = b.snapshot;
    }

    public static Builder builder(String id, StageExecutor executor) {
        return new Builder(id, executor);
    }

    public static final class Builder {
        private final String id;
        private final StageExecutor executor;
        private List<String> dependsOn = new ArrayList<>();
        private Function<RunContext, GateResult> entryGate = ctx -> GateResult.ok();
        private BiFunction<StageResult, RunContext, GateResult> exitGate =
                (r, ctx) -> r.status == StageStatus.PASSED ? GateResult.pass() : GateResult.fail(r.notes);
        private RetryPolicy retryPolicy = new RetryPolicy();
        private StageExecutor fallbackExecutor;
        private boolean requiresApproval;
        private Consumer<RunContext> rollback;
        private Supplier<Object> snapshot;

        private Builder(String id, StageExecutor executor) {
            this.id = id;
            this.executor = executor;
        }

        public Builder dependsOn(String... deps) {
            this.dependsOn = new ArrayList<>(List.of(deps));
            return this;
        }

        public Builder entryGate(Function<RunContext, GateResult> entryGate) {
            this.entryGate = entryGate;
            return this;
        }

        public Builder exitGate(BiFunction<StageResult, RunContext, GateResult> exitGate) {
            this.exitGate = exitGate;
            return this;
        }

        public Builder retryPolicy(RetryPolicy retryPolicy) {
            this.retryPolicy = retryPolicy;
            return this;
        }

        public Builder fallbackExecutor(StageExecutor fallbackExecutor) {
            this.fallbackExecutor = fallbackExecutor;
            return this;
        }

        public Builder requiresApproval(boolean requiresApproval) {
            this.requiresApproval = requiresApproval;
            return this;
        }

        public Builder rollback(Consumer<RunContext> rollback) {
            this.rollback = rollback;
            return this;
        }

        public Builder snapshot(Supplier<Object> snapshot) {
            this.snapshot = snapshot;
            return this;
        }

        public StageNode build() {
            return new StageNode(this);
        }
    }
}
