# Setup

## Prerequisites

- JDK 21 (`java --version`)
- Maven 3.8+ (`mvn --version`) — must be on `PATH`; the orchestrator's
  `unit_tests` stage shells out to `mvn`, so scenario runs fail without
  it
- `grep` (used by the brownfield scenario's codebase-impact scan; present
  on macOS/Linux by default)

## Install

```bash
git clone <this-repo>
cd agentic-url-shortener-java
mvn -q -DskipTests package
```

This builds both modules and produces
`orchestrator/target/orchestrator-cli.jar`.

## Run the test suite

```bash
mvn test
```

The repo ships no test sources of its own. Scenario test classes
(`AliasAndExpiryTest`, `ConcurrentClicksTest`, `SecurityHardeningTest`)
are written into each disposable workspace by the scenario's
`implementation` stage, then executed by `RunMavenTestsExecutor` via
`mvn -B test -pl urlshortener -Dtest=<TestClass>`.

The seeded click-counter race (the `@Disabled`
`ConcurrentClicksTest`) is expected to stay `@Disabled` in the repo's
own copy — the brownfield scenario removes the marker on a disposable
copy as a live demonstration of the fix.

## Run the URL shortener API

```bash
mvn spring-boot:run -pl urlshortener
```

Listens on port `8000` (`server.port=8000` in
`urlshortener/src/main/resources/application.properties`). Then, in
another terminal:

```bash
curl -X POST localhost:8000/api/urls -H 'content-type: application/json' \
  -d '{"url": "https://example.com"}'
curl -i localhost:8000/<code-from-above>
curl localhost:8000/api/urls/<code>/analytics
```

## Run an orchestrator scenario

Every scenario's `implementation`/`documentation` stages write real
files under `--target-dir`, so always point it at a disposable workspace
copy — never at this repo's own checkout. The copy holds the
`urlshortener` module plus the parent `pom.xml` (the same layout
`ScenarioCommon.copyUrlShortenerSource` produces):

```bash
mkdir -p /tmp/my-greenfield-run
cp -r urlshortener orchestrator /tmp/my-greenfield-run/
cp pom.xml /tmp/my-greenfield-run/
cp -r .mvn /tmp/my-greenfield-run/   # carries the local JVM flags; harmless elsewhere
java -jar orchestrator/target/orchestrator-cli.jar run greenfield --run-id my-greenfield-run --target-dir /tmp/my-greenfield-run
java -jar orchestrator/target/orchestrator-cli.jar status my-greenfield-run
java -jar orchestrator/target/orchestrator-cli.jar approve my-greenfield-run release_readiness --note "ship it"
java -jar orchestrator/target/orchestrator-cli.jar metrics --run my-greenfield-run
```

The `ambiguous` scenario pauses at `requirements` first:

```bash
mkdir -p /tmp/my-ambiguous-run
cp -r urlshortener /tmp/my-ambiguous-run/
cp pom.xml /tmp/my-ambiguous-run/
java -jar orchestrator/target/orchestrator-cli.jar run ambiguous --run-id my-ambiguous-run --target-dir /tmp/my-ambiguous-run
java -jar orchestrator/target/orchestrator-cli.jar status my-ambiguous-run          # requirements: awaiting_approval
java -jar orchestrator/target/orchestrator-cli.jar approve my-ambiguous-run requirements --note "scope approved"
java -jar orchestrator/target/orchestrator-cli.jar status my-ambiguous-run          # design/implementation/... now ran
java -jar orchestrator/target/orchestrator-cli.jar approve my-ambiguous-run release_readiness --note "ship it"
```

See `docs/scenarios/*.md` for the per-scenario breakdowns, including the
exact file changes each run makes.
