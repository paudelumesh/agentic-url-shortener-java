# Scenario: Ambiguous — Security Hardening

Workflow `ambiguous`, built by
`com.agentic.orchestrator.scenarios.AmbiguousScenario::buildWorkflow`.
Run against a fresh disposable workspace copy (the `urlshortener`
module plus the parent `pom.xml`), created per
`ScenarioCommon.copyUrlShortenerSource` — never the real repo.

## 1. Raw requirement

> Make it more secure.

This literal string is the run's actual `raw_requirement` field in the
requirements stage output — this is the one scenario where the raw input
is stored verbatim, because the whole point is that this three-word
sentence is not directly actionable.

## 2. Decomposition

**`requirements` stage** (`RequirementsExecutor`) normalizes it:

- interpretation: "More secure" is not actionable as stated; normalized
  into three concrete, independently verifiable sub-requirements based
  on the service's current threat exposure.
- assumptions: rate limiting (already implemented) is in scope for
  confirmation, not redesign; "secure" does not include
  authentication/user accounts — out of scope, flagged as a limitation;
  a static bearer token is an acceptable stand-in for real per-user
  auth in this prototype.
- open questions:
  - Should DELETE ownership be per-user in a future iteration, not a
    single shared token?
  - Should the target-URL denylist also block DNS names that resolve to
    internal IPs at request time (full SSRF protection), or is a static
    host/IP-literal check sufficient for now?
- sub-requirements:
  - Confirm rate limiting is wired to `POST /api/urls` and `GET /{code}`
    (already true).
  - Reject shortening targets that point at loopback/private/link-local
    hosts (open-redirect/SSRF-lite guard).
  - Require a bearer token header on `DELETE /api/urls/{code}`.

Unlike greenfield/brownfield, **this stage itself requires human
approval** (`requiresApproval(true)` on the `requirements` node) before
`design` can proceed — the workflow gates on the human approving the
normalized scope, not just on producing it.

**`design` stage** (`DesignExecutor`, runs only after the
`requirements` approval):

- Extend `UrlValidator`: reject loopback/private/link-local target
  hosts.
- Add `OWNER_TOKEN` to `AppConfig`.
- Require the `X-Owner-Token` header on `DELETE /api/urls/{code}`, else
  403.
- Risk: static shared token is a stopgap, not real per-user
  authorization.

## 3. Orchestration

**DAG** (from `AmbiguousScenario::buildWorkflow`):

| node | depends_on |
|---|---|
| `requirements` | — (`requiresApproval(true)`) |
| `design` | `requirements` |
| `implementation` | `design` |
| `unit_tests` | `implementation` |
| `documentation` | `implementation` |
| `release_readiness` | `unit_tests`, `documentation` (parallel sync point; `requiresApproval(true)`, guarded by the combined destructive-migration + test-coverage guardrail) |

This is the only one of the three scenarios with **two** approval gates
in the DAG: `requirements` and `release_readiness`.

Expected run:

```
$ java -jar orchestrator/target/orchestrator-cli.jar run ambiguous --run-id demo-ambiguous --target-dir /tmp/agentic-demo/ambiguous
run: demo-ambiguous  workflow: ambiguous
  [awaiting_approval] requirements
  [          pending] design
  [          pending] implementation
  [          pending] unit_tests
  [          pending] documentation
  [          pending] release_readiness

$ java -jar orchestrator/target/orchestrator-cli.jar approve demo-ambiguous requirements --note "normalized scope approved: rate-limit confirmation, open-redirect guard, owner-token DELETE gate; per-user auth explicitly deferred"
run: demo-ambiguous  workflow: ambiguous
  [           passed] requirements
  [           passed] design
  [           passed] implementation
  [           passed] unit_tests
  [           passed] documentation
  [awaiting_approval] release_readiness

$ java -jar orchestrator/target/orchestrator-cli.jar approve demo-ambiguous release_readiness --note "security hardening tests pass; ship it"
run: demo-ambiguous  workflow: ambiguous
  [           passed] requirements
  [           passed] design
  [           passed] implementation
  [           passed] unit_tests
  [           passed] documentation
  [           passed] release_readiness
```

The engine holds the entire downstream DAG at the gate until the human
approves `requirements`; `design` through `documentation` then run
immediately once unblocked.

**Approval interactions:** two human approvals, in sequence —
`requirements`, then `release_readiness`.

## 4. Validation

**`implementation` stage** (`ImplementationExecutor`) actually changes 4
real files in the disposable workspace:

- `urlshortener/src/main/java/com/agentic/urlshortener/domain/UrlValidator.java`
  (V3: rejects loopback/private/link-local target hosts)
- `urlshortener/src/main/java/com/agentic/urlshortener/config/AppConfig.java`
  (adds `OWNER_TOKEN`)
- `urlshortener/src/main/java/com/agentic/urlshortener/web/UrlController.java`
  (requires the `X-Owner-Token` header on `DELETE /api/urls/{code}`,
  403 on mismatch)
- `urlshortener/src/test/java/com/agentic/urlshortener/SecurityHardeningTest.java`
  (new test)

Schema change: `none`. New dependencies: none. Rationale: "Implemented
the 3 approved sub-requirements."

**`unit_tests` stage** runs the real subprocess
`mvn -B test -pl urlshortener -Dtest=SecurityHardeningTest` inside the
disposable workspace, parsing the `Tests run:` summary line into
`tests_total`/`tests_passed`.

**`release_readiness` guardrail outcome:** `schema_change: "none"`, so
the destructive-migration guardrail does not fire on schema grounds —
the node still requires approval because `requiresApproval(true)` is set
unconditionally (same as the other two scenarios), and the coverage
threshold passes on the real test result.

## 5. Risks and limitations

Carried from `docs/testing-and-tradeoffs.md`, as they apply to this run,
plus the two `open_questions` the requirements executor itself surfaced
(reproduced verbatim in section 2):

- **Open question 1:** "Should DELETE ownership be per-user in a future
  iteration, not a single shared token?" — directly reflected in the
  limitation "The ambiguous scenario's owner-token check is a static
  shared secret, not per-user auth."
- **Open question 2:** "Should the target-URL denylist also block DNS
  names that resolve to internal IPs at request time (full SSRF
  protection), or is a static host/IP-literal check sufficient for now?"
  — the implementation only rejects literal loopback/private/link-local
  hosts (`UrlValidator`), not DNS names that resolve to internal IPs at
  request time; a determined attacker using DNS rebinding is not covered.
- **No live LLM call.** The "ambiguity resolution" performed by
  `RequirementsExecutor` is a fixed, deterministic normalization of one
  specific three-word input, not a general requirement-understanding
  capability — this run demonstrates the orchestration mechanism for
  gating on human approval of interpreted scope, not autonomous
  ambiguity resolution.
- **Whole-file rewrite, not patch-based.** `ImplementationExecutor`
  overwrites `UrlValidator.java`, `AppConfig.java`, and
  `UrlController.java` from fixed string constants — running this
  scenario against a tree already modified by (for example) the
  greenfield scenario would silently drop the other scenario's changes;
  runs avoid that by targeting their own pristine disposable copy.
- **Static shared bearer token, not per-user auth** — `OWNER_TOKEN` in
  `AppConfig` is a single shared secret checked via the `X-Owner-Token`
  header on `DELETE`, an explicitly-flagged stopgap per the design
  stage's own risk note.
