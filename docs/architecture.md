# Architecture Overview

Two Maven modules plus a composition layer, per
`docs/superpowers/specs/2026-08-16-agentic-url-shortener-design.md`
(a design spec from the Python original, kept verbatim).

## `urlshortener` — the product

A Spring Boot 3.3.5 application (Java 21, `spring-boot-starter-web` +
`spring-boot-starter-jdbc`, `sqlite-jdbc`).

- `domain/` — pure logic: `CodeGenerator` (base62 generation +
  collision retry), `UrlValidator` (URL/alias/expiry rules — evolves per
  scenario, see below), `TokenBucketRateLimiter`.
- `repository/` — SQLite access: `Database` (SQLite `DataSource` in WAL
  journal mode + versioned migration runner), `UrlsRepository`,
  `ClicksRepository`; `UrlRecord`/`ClickStats` data carriers. Schema
  migrations live in `src/main/resources/db/migration/V1__init.sql`
  (scenario runs add V2/V3 files).
- `web/` — Spring MVC controllers: `UrlController` (create/delete),
  `RedirectController` (302 redirect + click recording),
  `AnalyticsController`, `HealthController`; `RateLimitFilter` (servlet
  filter applying the token bucket), `RestExceptionHandler`/`ApiException`
  (error mapping), `web/dto/` request/response records.
- `config/` — `AppConfig` (constants: `CODE_LENGTH`, rate-limit
  settings, `BASE_URL`; `OWNER_TOKEN` added by the ambiguous scenario),
  `ShortenerBeans` (bean wiring).
- `application.properties` — `server.port=8000`,
  `spring.jackson.property-naming-strategy=SNAKE_CASE` (JSON field names
  stay snake_case), `app.db-path=urlshortener.db`.

## `orchestrator` — the engine

- `StageExecutor` / `RunContext` — the vocabulary every stage speaks:
  `StageExecutor.run(context) -> StageResult`, and an append-only,
  versioned `RunContext` that gives every stage decision lineage.
- `StageNode` / `Workflow` — `StageNode` (entry/exit gates, `RetryPolicy`,
  fallback, rollback, `requiresApproval`) and `Workflow` (a validated
  DAG; cycles throw `CycleException`).
- `Engine` — the scheduler: dispatches ready nodes (dependencies
  satisfied) in parallel via a fixed thread pool
  (`Executors.newFixedThreadPool` — no virtual threads, this port keeps
  the pool classic), applies retry/backoff, falls back, rolls back and
  blocks downstream on unrecoverable failure, pauses at approval
  checkpoints, honors safe-stop, and triggers re-planning (`Replan`)
  whenever a stage produces a new context version.
- `RunStore` / `EventLog` — every transition is appended to
  `runs/<run_id>/events.jsonl` immediately; `runs/<run_id>/state.json`
  is checkpointed via `RunStore` so a run can be inspected or resumed
  from a separate process. `state.json` is checkpointed once per
  dispatch batch, not after every individual transition, so a crash
  mid-batch could leave `state.json` slightly behind the event log
  (though the event log itself remains a complete audit trail).
- `Guardrails` / `Policy` — composable rules (secrets, destructive
  migrations, new dependencies, test coverage) used as `exit_gate`s.
- `Metrics` — success rate, retry/rollback frequency, MTTR, latency,
  computed from the event log.
- `Json` / `Timestamps` — Jackson helpers for state/event
  serialization.
- `cli/OrchestrateCli` — the `orchestrate` CLI (picocli; packaged as
  `orchestrator-cli.jar` via the maven-shade-plugin); the one place
  allowed to import both `orchestrator` and `scenarios` (the engine
  itself never imports `scenarios` or `urlshortener`).

## `scenarios` — what ties them together

Each scenario (`GreenfieldScenario`, `BrownfieldScenario`,
`AmbiguousScenario`, registered in `ScenarioRegistry`) builds a
`Workflow` of the same shape —
`requirements -> design -> implementation -> [unit_tests, documentation] ->
release_readiness` — wired to deterministic `StageExecutor`s that read
and write real files under a `target_dir`. `ScenarioCommon` holds the
reusable pieces: `copyUrlShortenerSource` (copies the `urlshortener`
module + parent `pom.xml` onto a disposable workspace),
`RunMavenTestsExecutor` (a real subprocess `mvn -B test -pl
urlshortener` run, parsing the `Tests run:` summary line),
`grepImpactedFiles` (codebase-impact scanning via the `grep` binary), and
`ReleaseReadinessExecutor` (aggregates upstream outputs for the guardrail
`exit_gate`).

## Data flow

```
requirement text
     |
     v
[requirements] -> NormalizedRequirement (context v1)
     |
     v
[design] -> TaskPlan, reads requirements v1 (+ codebase impact scan if brownfield)
     |
     v
[implementation] -> writes real files, records diff summary
     |
     +---------------+
     v                v
[unit_tests]    [documentation]   <- parallel, both depend on implementation
     |                |
     +-------+--------+           <- sync point
             v
     [release_readiness] -> guardrail checks, human approval, go/no-go
```

If `requirements` is rejected and re-run, its context version bumps; the
engine's `invalidate_stale` walk (via `Replan`) marks every downstream
node whose recorded `input_versions` are now stale back to `pending`, so
only the affected subgraph re-executes.

## Key decisions

- **Deterministic executors, not live LLM calls** — every scenario executor
  is plain Java with a fixed transformation. The `StageExecutor` interface
  is the seam where a real model call would plug in; this port does not
  wire one, for reproducibility and zero-cost runs (see
  `docs/testing-and-tradeoffs.md`).
- **Whole-file rewrites, not line-level patches** — each scenario's
  implementation executor writes complete new file contents from a known
  string constant rather than diffing/patching. Simpler and fully
  deterministic, at the cost of the three scenarios being independent
  demonstrations against a fresh copy of the codebase rather than
  composable changes stacked on the same working tree — documented in
  `docs/testing-and-tradeoffs.md`.
- **Fixed thread-pool scheduler, JSON-file persistence** — sufficient for a
  single-process prototype with a handful of parallel branches; not
  horizontally scalable (would need a durable queue for that).
- **SQLite in WAL mode.** `Database` sets `JournalMode.WAL`, so the
  concurrency story is SQLite's own writer serialization plus the
  brownfield scenario's atomic `UPDATE` fix.
