# Final Engineering Summary

## Plan and rationale

Built as two independent Maven modules — a URL shortener service
(`urlshortener`, Spring Boot 3.3.5 + SQLite) and a domain-agnostic
agentic orchestration engine (`orchestrator`) — plus three scenarios that
demonstrate the engine driving real changes to the service's codebase.
This is a Java 21 port of the Python original. See
`docs/superpowers/specs/2026-08-16-agentic-url-shortener-design.md` for
the full design rationale (from the Python project, kept verbatim).

## Artifacts

- Working prototype: `urlshortener/`, `orchestrator/`,
  `orchestrator/.../scenarios/`.
- Architecture: `docs/architecture.md`.
- Setup: `docs/setup.md`.
- Three scenario breakdowns: `docs/scenarios/greenfield.md`,
  `docs/scenarios/brownfield.md`, `docs/scenarios/ambiguous.md`.
- Reliability metrics (`Metrics`: success rate, retry/rollback
  frequency, MTTR, latency) are computed from the event log; run all
  three scenarios end to end and then
  `java -jar orchestrator/target/orchestrator-cli.jar metrics` to see
  them. No pre-captured demo runs are bundled with this port — reproduce
  them locally with the commands in the README.

Note on interpreting `success_rate`: it counts a node that was paused
for human approval and then approved as a clean "pass" (it does not
separately track approval-gated passes), and `total_nodes` only counts
nodes that were actually dispatched (a node blocked behind an unapproved
gate and never run is excluded from the denominator) — so a 100% success
rate reflects "everything that ran, ran cleanly," not "zero human
intervention was needed."

## Risks, trade-offs, and validation

See `docs/testing-and-tradeoffs.md` for the full list; the two most
consequential for a reviewer:

1. Scenarios are independent demonstrations (fresh workspace per
   scenario), not composable changes on one working tree — a real
   rollout needs patch-based, not whole-file-rewrite, implementation
   executors.
2. No live LLM is wired into any `StageExecutor` — this prototype
   demonstrates the orchestration mechanism (DAG, gates, retries,
   rollback, approval, replanning, metrics) with deterministic stage
   logic; a production version would substitute a real model call
   behind the same `StageExecutor.run(context) -> StageResult`
   interface.

## Assumptions

- SQLite (in WAL mode) is an acceptable datastore for this prototype's
  scale.
- A single shared owner token is an acceptable stand-in for real
  per-user authorization on `DELETE`, pending real auth (flagged in the
  ambiguous scenario's own `open_questions`).
- Reviewers have JDK 21 and Maven 3.8+ with Maven on `PATH` — the
  orchestrator's scenario tests shell out to `mvn` at runtime; no
  external services or Docker required.

## Limitations

Carried in full from `docs/testing-and-tradeoffs.md`: no live LLM calls,
non-composable scenario demos, in-memory rate limiter, synchronous
click-write, static owner-token auth, single-process fixed-pool
scheduler. Rollback is a per-node, caller-supplied callback the engine
invokes on unrecoverable failure — not automatic file-snapshotting —
and none of the three scenarios currently configure one, since their
whole-file-rewrite `implementation` executors are idempotent and have
nothing mid-write to undo.
