# Agentic Software Engineering System — URL Shortener

Design spec. Date: 2026-08-16.

## 1. Problem Statement

Build a working prototype that demonstrates an **agentic execution model** for
software engineering: given a requirement, the system interprets intent,
decomposes it into a dependency graph of SDLC tasks, executes those tasks
(producing real code/tests/docs), validates the results, and surfaces
decisions/risks for human approval at defined checkpoints — with bounded
autonomy, not full automation and not a linear script.

The chosen product scenario (URL shortener) is the *subject matter* the
orchestrator operates on. The orchestrator itself is the primary deliverable
and evaluation target ("Workflow Orchestration" is called out as the
critical differentiator in the assignment).

## 2. Scope

In scope:
- A working URL shortener service (FastAPI + SQLite): create/redirect/analytics/delete APIs, reliability features (rate limiting, collision-resistant codes, validation), tests, OpenAPI schema.
- A domain-agnostic agentic orchestration engine: explicit DAG with entry/exit gates, sequential + parallel execution with sync points, persistent cross-stage context with decision lineage, human approval checkpoints, bounded retry/fallback/rollback/safe-stop, policy guardrails, observability + reliability metrics (success rate, retry/rollback frequency, MTTR, end-to-end latency), dynamic re-planning on upstream change.
- A CLI to run, inspect, approve/reject, and resume orchestrator runs, and to print metrics.
- Three demonstration scenarios run through the engine against the shortener codebase: greenfield, brownfield, ambiguous.
- Documentation: architecture overview, setup instructions, testing approach/limitations/trade-offs, and one write-up per scenario showing decomposition + orchestration + validation.

Out of scope (explicitly, to keep this bounded and YAGNI-compliant):
- Real LLM API calls. Stage executors are deterministic code behind an `Executor` interface designed so a real LLM call could be substituted later (documented as a limitation, not built).
- Distributed/multi-node orchestration, queues, or a UI. CLI + local process only.
- Postgres/Redis/Docker. SQLite file DB, no external services required to run.
- Auth/user accounts on the shortener (noted as a real gap the "ambiguous" scenario itself surfaces and partially addresses).

## 3. Architecture

Two independent packages in one repo:

```
src/urlshortener/    the product under change
src/orchestrator/    the engine that changes it (domain-agnostic)
scenarios/           per-scenario requirement input + executor wiring
tests/               unit + integration tests for both packages
docs/                architecture, setup, testing/trade-offs, scenario write-ups
runs/                persisted run state/event logs (sample runs checked in)
```

The orchestrator has **no import-time dependency on urlshortener internals**
beyond scenario-specific executor implementations in `scenarios/`. This is
what makes it a real engine rather than scenario-specific glue: the DAG
executor, gate logic, retry/rollback, context store, and metrics are tested
against fake in-memory executors, independent of the shortener domain.

### 3.1 URL Shortener Service

- **API layer** (`src/urlshortener/api/`): FastAPI routers.
  - `POST /api/urls` — body `{url, custom_alias?, expires_in_seconds?}` → `{code, short_url, expires_at}`. Idempotent on `(url, custom_alias)` pair within a TTL window.
  - `GET /{code}` — 302 redirect to target; records a click event asynchronously (does not block the redirect).
  - `GET /api/urls/{code}` — metadata (target, created_at, expires_at, active).
  - `GET /api/urls/{code}/analytics` — total clicks, clicks-by-day, top referrers, top user-agent families.
  - `DELETE /api/urls/{code}` — soft delete (marks inactive; 410 Gone on redirect).
  - `GET /healthz`, `GET /readyz`.
- **Domain layer** (`src/urlshortener/domain/`): code generation (base62, collision retry with bounded attempts), validation (URL scheme allowlist, alias charset/length, expiry bounds), rate limiter (token bucket, per-IP, in-memory with pluggable backend interface).
- **Repository layer** (`src/urlshortener/repository/`): SQLite via `sqlite3`/SQLAlchemy Core, versioned schema migrations (plain numbered SQL files applied at startup), `urls` and `clicks` tables. Click writes are queued through a single-writer path to avoid the read-modify-write race that the brownfield scenario fixes.
- **Tests**: unit tests for domain logic, integration tests via FastAPI `TestClient` against a temp SQLite file per test.

### 3.2 Orchestration Engine

- **Graph model** (`src/orchestrator/graph.py`): `StageNode(id, executor, depends_on: list[str], entry_gate, exit_gate, retry_policy, requires_approval: bool, rollback: Callable|None)`. A `Workflow` is a named set of `StageNode`s validated to be a DAG (cycle detection) at construction time.
- **Executor interface** (`src/orchestrator/executor.py`): `class StageExecutor: def run(self, context: RunContext) -> StageResult`. `StageResult` carries `status, outputs: dict, artifacts: list[Path], notes, risks: list[str]`. All default executors are plain Python; no network calls.
- **Context & lineage** (`src/orchestrator/context.py`): `RunContext` is an append-only ledger keyed by stage id → `ContextEntry(version, outputs, produced_by, timestamp, rationale)`. Never overwritten — a re-run appends a new version, so `git blame`-style lineage of *why* a decision changed is preserved and queryable.
- **Engine / scheduler** (`src/orchestrator/engine.py`): topological execution; nodes with satisfied dependencies and no unmet entry gate run immediately, independent branches run concurrently via a thread pool (I/O-light, CPU-light work — threads are sufficient, no need for multiprocessing), a node with multiple dependents is a natural sync point (it only starts once **all** its dependencies have exited `PASSED`).
- **Gates**: `entry_gate(context) -> GateResult(ok, reason)` checked before a node starts; `exit_gate(result, context) -> GateResult` checked after. `needs_approval` is a specific `GateResult` outcome that parks the node in `AWAITING_APPROVAL` and persists state; the engine process returns control (not a blocking wait) so approval happens out-of-process via CLI.
- **Retry/fallback/rollback/safe-stop** (`src/orchestrator/policy.py` + per-node `RetryPolicy(max_attempts, backoff_seconds)`):
  - Retry: on `FAILED` exit, retry up to `max_attempts` with backoff, re-invoking the same executor.
  - Fallback: if retries exhaust, and a `fallback_executor` is configured, it runs once; its result is tagged `via_fallback=True` in the context for lineage.
  - Rollback: nodes that touch the filesystem declare a `snapshot()`/`restore()` pair; the engine snapshots before running and restores on unrecoverable failure, then marks all dependents `INVALIDATED`.
  - Safe-stop: a run-level flag checked between node dispatches; when set (via CLI `orchestrate stop <run_id>`), no new nodes are dispatched, in-flight nodes finish, and the run persists as `STOPPED` (resumable).
- **Re-planning** (`src/orchestrator/replan.py`): each `ContextEntry` carries the version of every upstream entry it was built from. When a stage is manually re-run (e.g. after `request-changes`) and produces a new version, the engine diffs recorded input-versions across all nodes and marks any node whose recorded input is now stale as `INVALIDATED`, re-queuing only that subgraph.
- **Policy guardrails** (`src/orchestrator/guardrails.py`): rule functions run inside `exit_gate` for specific nodes — e.g. `no_secrets_in_diff`, `destructive_migration_requires_approval`, `test_coverage_threshold`, `new_dependency_requires_approval`. Violations force `needs_approval` or `fail` depending on severity (config-driven, not hardcoded per rule).
- **Observability** (`src/orchestrator/events.py`, `src/orchestrator/metrics.py`): every state transition appends a structured JSON event (`run_id, node_id, from_state, to_state, timestamp, attempt, reason`) to `runs/<run_id>/events.jsonl`. `metrics.py` derives: success rate (nodes/runs passed without intervention), retry frequency, rollback frequency, MTTR (time from first `FAILED` to eventual `PASSED`/`ROLLED_BACK` resolution), end-to-end run latency. `orchestrate metrics` prints an aggregate report across all persisted runs.
- **Persistence** (`src/orchestrator/store.py`): run state (`RunContext` + node statuses) serialized to `runs/<run_id>/state.json` after every transition — this is what makes `resume`/`approve` work as separate CLI invocations against a stopped process.

### 3.3 CLI

`orchestrate` (`src/orchestrator/cli.py`, installed via `pyproject.toml` entry point):
- `orchestrate run <scenario>` — start a scenario's workflow, run until completion or first `AWAITING_APPROVAL`/`STOPPED`.
- `orchestrate status <run_id>` — print DAG with per-node status.
- `orchestrate approve <run_id> <node_id> [--note]` / `orchestrate reject <run_id> <node_id> --note "..."` — resolve a checkpoint; reject re-queues the node's upstream for revision (goes to the requirements/design stage depending on config) rather than failing the run outright.
- `orchestrate resume <run_id>` — continue after approval/stop.
- `orchestrate stop <run_id>` — request safe-stop.
- `orchestrate metrics [--run <run_id>]` — print reliability metrics.

### 3.4 The Three Scenarios (`scenarios/`)

Each scenario is a `Workflow` built from the same reusable stage-node
*shapes* (requirements → design → implementation → [unit_tests ∥ docs] →
release_readiness) with scenario-specific executors and gate configs:

1. **Greenfield** — `scenarios/greenfield_custom_alias.py`. Requirement: "Let users pick a custom short code and an optional expiry for their link." Full DAG, no pre-existing code to reconcile; design stage produces a task list, implementation stage actually adds the fields/endpoints/migration to `src/urlshortener/`, tests run for real, docs updated for real. Approval checkpoint before `release_readiness` exits.
2. **Brownfield** — `scenarios/brownfield_click_counter_fix.py`. Requirement: "Clicks recorded under concurrent load are sometimes lost — fix it and make the analytics query efficient at scale." Design stage runs a codebase-impact scan first (identifies `repository/clicks.py` and the `/analytics` query as impacted), demonstrating architectural reasoning about an existing system before any code changes. Implementation fixes the read-modify-write race with an atomic increment / append-only click log, and adds an index. Approval checkpoint before the schema-affecting change proceeds (guardrail: destructive/structural migration requires approval).
3. **Ambiguous** — `scenarios/ambiguous_security_hardening.py`. Requirement: "Make it more secure." The requirements stage cannot proceed to design without normalizing this — it produces a `NormalizedRequirement` listing candidate sub-requirements (rate-limit abuse, validate/allowlist redirect targets to block open-redirect abuse, restrict `DELETE` to a token-based owner check) with assumptions and open questions, and **gates on human approval of the normalized scope** before design/implementation touch any code. This is the scenario that demonstrates requirement-understanding and ambiguity handling explicitly.

Each scenario has a companion doc in `docs/scenarios/` capturing: the raw requirement, the normalized/decomposed tasks with dependencies, a rendering of the executed DAG with statuses, the approval interaction, and the validation/risk notes the run produced — i.e., the actual run output, not a hypothetical narrative.

## 4. Data Flow

```
requirement text
     │
     ▼
[requirements]  → NormalizedRequirement (problem, assumptions, open Qs, acceptance criteria)
     │  (context v1)
     ▼
[design]        → reads NormalizedRequirement (+ codebase impact scan if brownfield)
     │             → TaskPlan (ordered steps, files to touch, risks)
     │  (context v1, depends on requirements v1)
     ▼
[implementation]→ applies TaskPlan to src/urlshortener/, records diff summary
     │  (context v1, depends on design v1)
     ├──────────────┬───────────────
     ▼              ▼
[unit_tests]    [documentation]      ← parallel branch, both depend on implementation v1
     │              │
     └──────┬───────┘                ← sync point: release_readiness depends on BOTH
            ▼
     [release_readiness] → guardrail checks + go/no-go, human approval before PASS
```

If `requirements` is re-run after a `reject`, its context version bumps to
v2; `design`'s recorded input version (v1) is now stale, so `design` and
everything downstream is `INVALIDATED` and re-queued — `implementation`,
`unit_tests`, `documentation`, `release_readiness` all re-run against the
new requirement, but nothing upstream of `requirements` re-runs (there is
nothing upstream).

## 5. Error Handling

- **Executor exceptions** are caught by the engine and converted to `FAILED` `StageResult`s (never crash the run process) — retried per policy, then fallback, then the node is `FAILED` and downstream nodes become `BLOCKED`.
- **Gate exceptions** (a bug in a guardrail rule) are treated as `fail` conservatively (fail closed), logged, and surfed in `orchestrate status`.
- **Filesystem side effects** are always snapshotted before a node with `rollback` configured runs, so a failed `implementation` node never leaves partial file edits behind — `restore()` runs automatically.
- **CLI errors** (approving a non-existent run/node, resuming a run that isn't stopped) produce clear exit-code-1 messages, never stack traces.

## 6. Testing Approach

- **Orchestrator engine**: unit tests using fake in-memory `StageExecutor`s (no filesystem/urlshortener dependency) covering: linear execution, parallel sync points, entry/exit gate blocking, retry exhaustion → fallback, rollback + downstream invalidation, safe-stop mid-run, re-planning after a version bump, metrics computation against a fixture event log.
- **URL shortener**: unit tests for code generation/collision handling, validation, rate limiter; integration tests (FastAPI `TestClient` + temp SQLite) for the full create → redirect → analytics → delete lifecycle, including the concurrency fix (a test that fires concurrent click writes and asserts no lost updates — this is the regression test the brownfield scenario's `implementation` stage is judged against).
- **Scenarios**: each scenario has an integration test that runs the workflow against a disposable copy of `src/urlshortener/` (via a temp dir fixture) and asserts the expected end state (files changed, tests passing, approval checkpoint hit) — proving the scenario docs reflect real, reproducible runs, not hand-written narratives.

## 7. Risks, Trade-offs, Limitations (carried into final summary)

- **Deterministic executors instead of a live LLM**: chosen for reproducibility and zero-cost/zero-key runs; the `StageExecutor` interface is the seam where a real Claude call would plug in (documented explicitly as a limitation + extension point, not hidden).
- **Threaded, single-process scheduler**: sufficient for a prototype's parallel branches; not horizontally scalable — a real system would need a durable queue (e.g. Temporal/Celery) for multi-worker execution. Documented as a scaling trade-off.
- **Rollback is file-snapshot based, not git-integrated**: simpler and dependency-free, but coarser than a real VCS-based rollback; acceptable for a prototype, called out as a limitation.
- **Rate limiter is in-memory**: fine for a single-process prototype; would need a shared backend (Redis) for multi-instance deployment — interface is already pluggable to make that swap explicit rather than silent.
- **SQLite**: single-writer constraint is real; the click-write fix uses an append-only log specifically to sidestep lock contention, and this trade-off is written up in the brownfield scenario doc.

## 8. Deliverables Checklist (traced to assignment requirements)

- Working prototype → `src/urlshortener` + `src/orchestrator`, runnable via `uvicorn` and `orchestrate`.
- Architecture overview → `docs/architecture.md`.
- Three scenarios (greenfield/brownfield/ambiguous) → `scenarios/*.py` + `docs/scenarios/*.md` with real run output.
- Setup instructions → `docs/setup.md` + root `README.md`.
- Testing approach/limitations/trade-offs → `docs/testing-and-tradeoffs.md` (expands §6/§7 above).
- Final Engineering Summary (plan/rationale, artifacts, risks, assumptions, limitations) → `docs/final-summary.md`, written last, after real run output exists to reference.
