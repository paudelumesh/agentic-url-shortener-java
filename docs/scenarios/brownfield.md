# Scenario: Brownfield — Click-Counter Race Fix

Workflow `brownfield`, built by
`com.agentic.orchestrator.scenarios.BrownfieldScenario::buildWorkflow`.
Run against a fresh disposable workspace copy (the `urlshortener`
module plus the parent `pom.xml`), created per
`ScenarioCommon.copyUrlShortenerSource` — never the real repo.

## 1. Raw requirement

> Clicks recorded under concurrent load are sometimes lost — fix it and
> make the analytics query efficient at scale.

## 2. Decomposition

**`requirements` stage** (`RequirementsExecutor`) normalizes it to:

- problem: clicks recorded under concurrent load are sometimes lost;
  the analytics query should stay fast as click volume grows.
- assumptions: the loss is a read-modify-write race in
  `ClicksRepository.recordClick`, not a client-side retry issue; an
  index on `clicks(code)` is sufficient for the current analytics query
  shape.
- acceptance criteria: 50 concurrent click writes against one code
  result in `click_count == 50`; `ConcurrentClicksTest` passes without
  the `@Disabled` marker.

**`design` stage** (`DesignExecutor`) first runs a real
codebase-impact scan — `ScenarioCommon.grepImpactedFiles(targetDir,
"click_count")`, a real `grep -rl` subprocess over
`urlshortener/src` — before proposing a task plan, demonstrating
architectural reasoning over an *existing* system rather than greenfield
authoring:

- Replace the read-modify-write increment in
  `ClicksRepository.recordClick` with an atomic SQL UPDATE.
- Add an index on `clicks(code)` to keep the analytics query fast at
  scale.
- Remove the `@Disabled` marker from the concurrency regression test
  once fixed.
- Risk: an atomic UPDATE alone does not guarantee no `SQLITE_BUSY`
  under very high contention; `PRAGMA busy_timeout` mitigates it.

## 3. Orchestration

**DAG** (from `BrownfieldScenario::buildWorkflow`):

| node | depends_on |
|---|---|
| `requirements` | — |
| `design` | `requirements` |
| `implementation` | `design` |
| `unit_tests` | `implementation` (retry policy: `maxAttempts=2`, `backoffSeconds=0.2`) |
| `documentation` | `implementation` |
| `release_readiness` | `unit_tests`, `documentation` (parallel sync point; `requiresApproval(true)`, guarded by the combined destructive-migration + test-coverage guardrail) |

Expected run:

```
$ java -jar orchestrator/target/orchestrator-cli.jar run brownfield --run-id demo-brownfield --target-dir /tmp/agentic-demo/brownfield
run: demo-brownfield  workflow: brownfield
  [           passed] requirements
  [           passed] design
  [           passed] implementation
  [           passed] unit_tests
  [           passed] documentation
  [awaiting_approval] release_readiness

$ java -jar orchestrator/target/orchestrator-cli.jar approve demo-brownfield release_readiness --note "atomic increment verified by the concurrency test; ship it"
run: demo-brownfield  workflow: brownfield
  [           passed] requirements
  [           passed] design
  [           passed] implementation
  [           passed] unit_tests
  [           passed] documentation
  [           passed] release_readiness
```

**Approval interaction:** one human approval on `release_readiness`.

## 4. Validation

**`implementation` stage** (`ImplementationExecutor`) rewrites/adds 3
real files in the disposable workspace:

- `urlshortener/src/main/resources/db/migration/V2__clicks_index.sql`
  (`CREATE INDEX IF NOT EXISTS idx_clicks_code ON clicks(code);`)
- `urlshortener/src/main/java/com/agentic/urlshortener/repository/ClicksRepository.java`
  (V2: `recordClick` becomes a single atomic
  `UPDATE urls SET click_count = click_count + 1 WHERE code = ?`, plus
  the `clicks` row insert)
- `urlshortener/src/test/java/com/agentic/urlshortener/ConcurrentClicksTest.java`
  (regression test with the `@Disabled` marker removed)

Schema change: `additive`. Rationale recorded by the executor:
"Replaced read-modify-write increment with an atomic UPDATE; added
clicks(code) index; unmarked the regression test."

**`unit_tests` stage** runs the real subprocess
`mvn -B test -pl urlshortener -Dtest=ConcurrentClicksTest` inside the
disposable workspace (`RunMavenTestsExecutor`), parsing the `Tests run:`
summary line into `tests_total`/`tests_passed`. This is why Maven must be
on `PATH`.

**`release_readiness` guardrail outcome:** the guardrail combination
triggers because the diff includes a new migration file
(`V2__clicks_index.sql` — an index creation, additive not destructive,
but still a schema change), and the coverage threshold passes on the
real result. The node pauses (`awaiting_approval`, reason `"human
approval required"`) and passes once approved.

## 5. Risks and limitations

Carried from `docs/testing-and-tradeoffs.md`, as they apply to this run:

- **No live LLM call.** The three brownfield executors are deterministic
  Java; the "codebase-impact scan" is a real `grep`, not model-driven
  code comprehension — this demonstrates the mechanism for gating a fix
  behind an impact analysis, not autonomous root-cause diagnosis.
- **Atomic UPDATE mitigates but does not fully eliminate contention
  failure.** The design stage's own risk note is carried through
  unresolved: an atomic UPDATE alone does not guarantee no `SQLITE_BUSY`
  under very high contention — the run's single concurrency test (50
  concurrent writes) passing is not proof against arbitrarily higher
  contention without `busy_timeout` tuning.
- **Whole-file rewrite, not patch-based.** `ImplementationExecutor`
  overwrites `ClicksRepository.java` from a fixed string constant rather
  than patching the file that the (real, grep-based) impact scan
  identified — safe here only because the run targets a pristine
  disposable copy.
- **Impact scan is textual, not semantic.** `grepImpactedFiles` matches
  a literal string (`click_count`) — a naive-but-real demonstration that
  a production version would need AST-aware or IDE-grade impact
  analysis, not raw grep.
