# Scenario: Greenfield — Custom Alias + Expiry

Workflow `greenfield`, built by
`com.agentic.orchestrator.scenarios.GreenfieldScenario::buildWorkflow`.
Run against a fresh disposable workspace copy (the `urlshortener`
module plus the parent `pom.xml`), created per
`ScenarioCommon.copyUrlShortenerSource` — never the real repo.

## 1. Raw requirement

> Let users pick a custom short code and an optional expiry for their link.

## 2. Decomposition

**`requirements` stage** (`RequirementsExecutor`) normalizes it to:

- problem: "Users want to choose their own short code and set an optional expiry."
- assumptions: custom aliases are case-sensitive, alphanumeric, 3–32
  chars; expiry is measured in seconds from creation, capped at 1 year.
- acceptance criteria: `POST /api/urls` accepts optional `custom_alias`
  and `expires_in_seconds`; a taken alias returns 409; an expired link
  returns 410 on redirect.

**`design` stage** (`DesignExecutor`) proposes:

- Add nullable `expires_at` column via migration `V3`.
- Extend `UrlValidator` with `validateAlias`/`validateExpiry`.
- Extend `UrlsRepository.create` to accept `code`/`expires_at`.
- Extend `CreateUrlRequest`/`UrlResponse` DTOs.
- Extend `UrlController` to pass through new fields, handle 409/422.
- Extend `RedirectController` to return 410 on expiry.
- Risk: alias collisions with previously auto-generated codes.

No approval gate on `requirements` in this scenario — the requirement
is already concrete, so `requirements`/`design` both run straight
through to `passed`.

## 3. Orchestration

**DAG** (from `GreenfieldScenario::buildWorkflow`):

| node | depends_on |
|---|---|
| `requirements` | — |
| `design` | `requirements` |
| `implementation` | `design` |
| `unit_tests` | `implementation` |
| `documentation` | `implementation` |
| `release_readiness` | `unit_tests`, `documentation` (parallel sync point; `requiresApproval(true)`, guarded by the combined destructive-migration + test-coverage guardrail) |

Expected run (jar built via `mvn -q -DskipTests package`):

```
$ java -jar orchestrator/target/orchestrator-cli.jar run greenfield --run-id demo-greenfield --target-dir /tmp/agentic-demo/greenfield
run: demo-greenfield  workflow: greenfield
  [           passed] requirements
  [           passed] design
  [           passed] implementation
  [           passed] unit_tests
  [           passed] documentation
  [awaiting_approval] release_readiness

$ java -jar orchestrator/target/orchestrator-cli.jar approve demo-greenfield release_readiness --note "custom alias + expiry verified by new tests; ship it"
run: demo-greenfield  workflow: greenfield
  [           passed] requirements
  [           passed] design
  [           passed] implementation
  [           passed] unit_tests
  [           passed] documentation
  [           passed] release_readiness
```

`documentation` and `unit_tests` both depend only on `implementation`,
so they run concurrently before the `release_readiness` sync point.

**Approval interaction:** one human approval on `release_readiness`.

## 4. Validation

**`implementation` stage** (`ImplementationExecutor`) rewrites these
real files inside the disposable workspace (`files_changed` from the
stage output):

- `urlshortener/src/main/java/com/agentic/urlshortener/domain/UrlValidator.java`
  (V2: adds `validateAlias`/`validateExpiry`)
- `urlshortener/src/main/resources/db/migration/V3__add_expiry.sql`
  (`ALTER TABLE urls ADD COLUMN expires_at TEXT;`)
- `urlshortener/src/main/java/com/agentic/urlshortener/repository/UrlRecord.java`
  (adds the expiry field)
- `urlshortener/src/main/java/com/agentic/urlshortener/repository/DuplicateCodeException.java`
  (new; thrown on taken alias)
- `urlshortener/src/main/java/com/agentic/urlshortener/repository/UrlsRepository.java`
  (`create` accepts code + expiry)
- `urlshortener/src/main/java/com/agentic/urlshortener/web/dto/CreateUrlRequest.java`
  (optional `custom_alias`, `expires_in_seconds` — snake_case via the
  global Jackson naming strategy)
- `urlshortener/src/main/java/com/agentic/urlshortener/web/dto/UrlResponse.java`
- `urlshortener/src/main/java/com/agentic/urlshortener/web/UrlController.java`
  (pass-through; 409 on `DuplicateCodeException`, 422 on invalid input)
- `urlshortener/src/main/java/com/agentic/urlshortener/web/RedirectController.java`
  (410 on expired link)
- `urlshortener/src/test/java/com/agentic/urlshortener/AliasAndExpiryTest.java`
  (new regression test)

Schema change: `additive`.

**`unit_tests` stage** runs the real subprocess
`mvn -B test -pl urlshortener -Dtest=AliasAndExpiryTest` inside the
disposable workspace (`RunMavenTestsExecutor`), parsing the `Tests run:`
summary line into `tests_total`/`tests_passed`.

**`release_readiness` guardrail outcome:** the exit gate requires human
approval because the diff includes a schema migration, and the coverage
threshold is met by the real test result. The node moves `running ->
awaiting_approval` (`"human approval required"`), then to `passed` once
approved.

## 5. Risks and limitations

Carried from `docs/testing-and-tradeoffs.md`, as they apply to this run:

- **No live LLM call.** The three greenfield executors are deterministic
  Java producing a fixed transformation — this demonstrates the
  orchestration mechanism (DAG, sync point, guardrail-gated approval),
  not autonomous requirement interpretation.
- **Whole-file rewrite, not patch-based.** `ImplementationExecutor`
  overwrites its files from fixed string constants rather than diffing
  against what's on disk — safe here only because the run targets a
  pristine disposable copy of the codebase.
- **Alias-collision risk flagged, not eliminated.** A custom alias could
  collide with a previously-issued random code; `UrlsRepository.create`
  only checks currently-stored rows, not any blocklist of
  "likely to be auto-generated" codes.
- **Migration is additive-only.** The guardrail treats `ALTER TABLE ADD
  COLUMN` as requiring approval simply because it's a schema change; it
  is not itself destructive, but the run still correctly pauses for a
  human sign-off before `release_readiness` passes.
