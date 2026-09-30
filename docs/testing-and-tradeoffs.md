# Testing Approach, Limitations, and Trade-offs

## Testing approach

- **`urlshortener`** — domain unit tests per module (`CodeGenerator`,
  `UrlValidator`, `TokenBucketRateLimiter`), integration tests per
  controller against a temp SQLite file, one full-lifecycle test, and
  one concurrency regression test (`ConcurrentClicksTest`, `@Disabled`
  until the brownfield scenario's fix lands).
- **`orchestrator`** — every engine capability (linear execution,
  parallel sync points, gates, retry/backoff, fallback, rollback +
  downstream blocking, approval pause/resume, reject/revise, safe-stop,
  re-planning, guardrails, metrics) is tested with fake in-memory
  `StageExecutor`s — zero dependency on `urlshortener` or `scenarios`,
  proving the engine is a real generic mechanism.
- **`scenarios`** — each scenario has an integration test that runs the
  real workflow against a disposable copy of the codebase
  (`ScenarioCommon.copyUrlShortenerSource`) and asserts the actual end
  state: files changed, the real `mvn` subprocess passed, the expected
  approval checkpoints were hit.

## Java-port-specific trade-offs

- **`@Disabled` instead of strict `xfail`.** The Python original marks
  the click-counter race with pytest's strict xfail; JUnit 5's nearest
  equivalent is `@Disabled`. The brownfield scenario removes the marker
  on the disposable copy, mirroring the original behavior.
- **Scenario tests shell out to `mvn`.** `RunMavenTestsExecutor` runs
  `mvn -B test -pl urlshortener -Dtest=<TestClass>` in the target
  directory and parses the `Tests run:` summary line. This makes Maven-on-
  `PATH` a hard runtime requirement for scenario runs (not just for
  building the repo).
- **Fixed thread pool, no virtual threads.** `Engine` dispatches ready
  nodes with `Executors.newFixedThreadPool`, not `Executors.newVirtualThreadPerTaskExecutor`.
  Sufficient for a handful of parallel branches; virtual threads would
  be the obvious upgrade if the engine grew many concurrent nodes.
- **SQLite in WAL mode.** `Database` sets `JournalMode.WAL` on the
  SQLite connection, so concurrent readers don't block the single
  writer — the brownfield fix (atomic `UPDATE urls SET click_count =
  click_count + 1`) still matters because the race was
  read-modify-write in Java, not at the database level.
- **SNAKE_CASE Jackson naming.** `spring.jackson.property-naming-strategy=SNAKE_CASE`
  keeps JSON field names snake_case (`expires_in_seconds`,
  `short_url`), matching the Python API's wire format. Java DTOs use
  camelCase records; serialization is the only place the two differ.

## Known limitations (carried from the Python original)

- **No live LLM calls.** Every `StageExecutor` in every scenario is
  deterministic Java with a fixed transformation, chosen for
  reproducibility and zero API cost/key requirement in this prototype.
  `StageExecutor.run(context) -> StageResult` is the seam where a real
  model call would be substituted.
- **Scenarios are independent, not composable on one working tree.**
  Each scenario's implementation executor rewrites whole files from a
  fixed string constant derived from the *pristine* base codebase, not
  from whatever the previous scenario left behind. Running greenfield
  then ambiguous against the *same* tree would have ambiguous's rewrite
  of `UrlValidator.java` silently drop greenfield's alias/expiry rules.
  Runs avoid this by targeting each scenario's own fresh workspace copy
  — a real multi-change rollout would need implementation executors
  that read-and-patch current file state (true diffing) instead of
  whole-file rewrites. Whole-file rewrites were chosen here for
  determinism and review clarity, at this explicit cost.
- **Rate limiter is in-memory.** `TokenBucketRateLimiter` is
  per-process; a multi-instance deployment would need a shared backend
  (Redis).
- **Rollback is a caller-supplied callback, not automatic file
  snapshotting.** `StageNode.rollback` is an optional callback the
  engine invokes on unrecoverable failure if the node configured one.
  It is *not* an automatic before/after file-snapshot-and-restore
  mechanism. None of the three scenarios currently configure a
  `rollback` callback on their `implementation` nodes, because each
  writes complete files from a fixed string constant — a whole-file
  rewrite is idempotent (safe to just re-run/overwrite) and has nothing
  mid-write to undo.
- **The `redirect` click write is synchronous, not backgrounded.** It is
  a handful of indexed SQLite statements (sub-millisecond);
  backgrounding it would make the click-count-immediately-after-
  redirect test flaky without an explicit wait, which is worse for a
  prototype than the small synchronous cost.
- **The ambiguous scenario's owner-token check is a static shared
  secret, not per-user auth.** Explicitly flagged as an open question in
  the scenario's own normalized requirement
  (`docs/scenarios/ambiguous.md`) — real authentication is out of scope
  for this prototype.
- **Single-process, fixed-pool scheduler.** Sufficient for a handful of
  parallel branches in one process; not horizontally scalable — a
  production orchestrator would need a durable, multi-worker queue.
- **`Engine` transitions node state from the pool without an explicit
  lock.** Safe in practice today (each dispatched batch touches disjoint
  node ids), but a future engine hardening pass should add explicit
  locking around transitions and context appends — notable given the
  brownfield scenario's own subject matter is exactly this class of
  bug.
- **`TokenBucketRateLimiter`'s per-key bucket map grows unboundedly**
  (one entry per distinct client IP/key seen, never evicted) —
  acceptable for a prototype's process lifetime, would need an eviction
  policy (e.g. LRU with a max size) for a long-running production
  deployment.
- **`Metrics`' `success_rate` does not distinguish approval-gated
  passes.** A node that was paused for human approval and then approved
  counts as a clean "pass" like any other; the metric does not
  separately track how many passes required intervention. Also,
  `total_nodes` only counts nodes that were actually dispatched — a node
  blocked behind an unapproved gate and never run is excluded from the
  denominator. So a 100% success rate reflects "everything that ran, ran
  cleanly," not "zero human intervention was needed."

## Risk notes carried into the Final Engineering Summary

See `docs/final-summary.md` for how these limitations map to concrete
risk/trade-off statements.
