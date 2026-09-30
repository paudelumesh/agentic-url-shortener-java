package com.agentic.orchestrator.scenarios;

import com.agentic.orchestrator.Guardrails;
import com.agentic.orchestrator.Policy;
import com.agentic.orchestrator.RetryPolicy;
import com.agentic.orchestrator.RunContext;
import com.agentic.orchestrator.StageExecutor;
import com.agentic.orchestrator.StageNode;
import com.agentic.orchestrator.StageResult;
import com.agentic.orchestrator.StageStatus;
import com.agentic.orchestrator.Workflow;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class BrownfieldScenario {
    private BrownfieldScenario() {
    }

    public static Workflow build(Path targetDir) {
        if (targetDir == null) {
            throw new IllegalArgumentException(
                    "target_dir is required — pass a disposable workspace copy "
                            + "(see ScenarioCommon.copyUrlShortenerSource), never the real repo");
        }
        StageNode requirements = StageNode.builder("requirements", new RequirementsExecutor()).build();
        StageNode design = StageNode.builder("design", new DesignExecutor(targetDir))
                .dependsOn("requirements").build();
        StageNode implementation = StageNode.builder("implementation", new ImplementationExecutor(targetDir))
                .dependsOn("design").build();
        StageNode unitTests = StageNode.builder("unit_tests",
                        new ScenarioCommon.RunMavenTestsExecutor(targetDir, "ConcurrentClicksTest"))
                .dependsOn("implementation")
                .retryPolicy(new RetryPolicy(2, 0.2))
                .build();
        StageNode documentation = StageNode.builder("documentation", new DocumentationExecutor(targetDir))
                .dependsOn("implementation").build();
        StageNode releaseReadiness = StageNode.builder("release_readiness",
                        new ScenarioCommon.ReleaseReadinessExecutor())
                .dependsOn("unit_tests", "documentation")
                .exitGate(Policy.combineGuardrails(
                        Guardrails.destructiveMigrationRequiresApproval(),
                        Guardrails.testCoverageThreshold(1.0)))
                .requiresApproval(true)
                .build();
        return new Workflow("brownfield",
                List.of(requirements, design, implementation, unitTests, documentation, releaseReadiness));
    }

    static class RequirementsExecutor extends StageExecutor {
        RequirementsExecutor() {
            name = "brownfield_requirements";
        }

        @Override
        public StageResult run(RunContext context) {
            Map<String, Object> outputs = new LinkedHashMap<>();
            outputs.put("problem",
                    "Clicks recorded under concurrent load are sometimes lost; the "
                            + "analytics query should stay fast as click volume grows.");
            outputs.put("assumptions", List.of(
                    "The loss is a read-modify-write race in ClicksRepository.recordClick, "
                            + "not a client-side retry issue.",
                    "An index on clicks(code) is sufficient for the current analytics query shape."));
            outputs.put("acceptance_criteria", List.of(
                    "50 concurrent click writes against one code result in click_count == 50.",
                    "ConcurrentClicksTest passes without the @Disabled marker."));
            return new StageResult(StageStatus.PASSED, outputs,
                    "Normalized brownfield requirement: click-counter race + analytics query efficiency.");
        }
    }

    static class DesignExecutor extends StageExecutor {
        private final Path targetDir;

        DesignExecutor(Path targetDir) {
            name = "brownfield_design";
            this.targetDir = targetDir;
        }

        @Override
        public StageResult run(RunContext context) {
            List<String> impacted;
            try {
                impacted = ScenarioCommon.grepImpactedFiles(targetDir, "click_count");
            } catch (Exception e) {
                return new StageResult(StageStatus.FAILED, Map.of(),
                        "impact scan failed: " + e.getMessage());
            }
            Map<String, Object> outputs = new LinkedHashMap<>();
            outputs.put("impacted_files", impacted);
            outputs.put("task_plan", List.of(
                    "Replace the read-modify-write increment in ClicksRepository.recordClick "
                            + "with an atomic SQL UPDATE.",
                    "Add an index on clicks(code) to keep the analytics query fast at scale.",
                    "Remove the @Disabled marker from the concurrency regression test once fixed."));
            outputs.put("risks", List.of(
                    "An atomic UPDATE alone does not guarantee no SQLITE_BUSY under very high "
                            + "contention; PRAGMA busy_timeout mitigates it."));
            return new StageResult(StageStatus.PASSED, outputs,
                    "Impact scan found " + impacted.size() + " file(s) referencing click_count.");
        }
    }

    static class ImplementationExecutor extends StageExecutor {
        private final Path targetDir;

        ImplementationExecutor(Path targetDir) {
            name = "brownfield_implementation";
            this.targetDir = targetDir;
        }

        @Override
        public StageResult run(RunContext context) {
            try {
                write("urlshortener/src/main/resources/db/migration/V2__clicks_index.sql", V2_SQL);
                write("urlshortener/src/main/java/com/agentic/urlshortener/repository/ClicksRepository.java",
                        CLICKS_REPOSITORY_V2);
                write("urlshortener/src/test/java/com/agentic/urlshortener/ConcurrentClicksTest.java",
                        CONCURRENT_CLICKS_TEST);
            } catch (IOException e) {
                return new StageResult(StageStatus.FAILED, Map.of(),
                        "failed to write scenario files: " + e.getMessage());
            }
            Map<String, Object> outputs = new LinkedHashMap<>();
            outputs.put("files_changed", List.of(
                    "urlshortener/src/main/resources/db/migration/V2__clicks_index.sql",
                    "urlshortener/src/main/java/com/agentic/urlshortener/repository/ClicksRepository.java",
                    "urlshortener/src/test/java/com/agentic/urlshortener/ConcurrentClicksTest.java"));
            outputs.put("schema_change", "additive");
            outputs.put("diff_preview", "CREATE INDEX IF NOT EXISTS idx_clicks_code ON clicks(code);");
            return new StageResult(StageStatus.PASSED, outputs,
                    "Replaced read-modify-write increment with an atomic UPDATE; "
                            + "added clicks(code) index; unmarked the regression test.");
        }

        private void write(String relative, String content) throws IOException {
            Path path = targetDir.resolve(relative);
            Files.createDirectories(path.getParent());
            Files.writeString(path, content);
        }
    }

    static class DocumentationExecutor extends StageExecutor {
        private final Path targetDir;

        DocumentationExecutor(Path targetDir) {
            name = "brownfield_documentation";
            this.targetDir = targetDir;
        }

        @Override
        public StageResult run(RunContext context) {
            try {
                Path docsDir = targetDir.resolve("docs");
                Files.createDirectories(docsDir);
                Path docPath = docsDir.resolve("api-changes.md");
                String existing = Files.exists(docPath) ? Files.readString(docPath) : "# API Changes\n";
                String section = "\n## Click-counter race condition fix (brownfield scenario)\n\n"
                        + "`ClicksRepository.recordClick` previously read `click_count`, incremented it in "
                        + "Java, then wrote it back — a read-modify-write race that lost updates under "
                        + "concurrent writers. It now uses a single atomic `UPDATE urls SET click_count = "
                        + "click_count + 1 WHERE code = ?` statement. An index on `clicks(code)` keeps the "
                        + "analytics query fast.\n";
                Files.writeString(docPath, existing + section);
            } catch (IOException e) {
                return new StageResult(StageStatus.FAILED, Map.of(),
                        "failed to write documentation: " + e.getMessage());
            }
            return new StageResult(StageStatus.PASSED,
                    Map.of("files_changed", List.of("docs/api-changes.md")),
                    "Documented the click-counter race fix.");
        }
    }

    private static final String V2_SQL = """
            CREATE INDEX IF NOT EXISTS idx_clicks_code ON clicks(code);
            """;

    private static final String CLICKS_REPOSITORY_V2 = """
            package com.agentic.urlshortener.repository;

            import java.time.Instant;
            import java.util.List;
            import javax.sql.DataSource;
            import org.springframework.jdbc.core.JdbcTemplate;

            public class ClicksRepository {
                private final JdbcTemplate jdbc;

                public ClicksRepository(DataSource dataSource) {
                    this.jdbc = new JdbcTemplate(dataSource);
                }

                public void recordClick(String code, String referrer, String userAgent) {
                    // Atomic increment: click_count = click_count + 1 happens entirely inside
                    // the database's own statement execution, eliminating the Java-level
                    // read-modify-write race from the previous implementation.
                    jdbc.update("UPDATE urls SET click_count = click_count + 1 WHERE code = ?", code);
                    jdbc.update("INSERT INTO clicks (code, timestamp, referrer, user_agent) VALUES (?, ?, ?, ?)",
                            code, Instant.now().toString(), referrer, userAgent);
                }

                public ClickStats analytics(String code) {
                    Integer total = jdbc.queryForObject(
                            "SELECT COUNT(*) FROM clicks WHERE code = ?", Integer.class, code);
                    List<ClickStats.ReferrerCount> top = jdbc.query(
                            "SELECT referrer, COUNT(*) AS c FROM clicks WHERE code = ? "
                                    + "GROUP BY referrer ORDER BY c DESC LIMIT 5",
                            (rs, i) -> new ClickStats.ReferrerCount(rs.getString("referrer"), rs.getInt("c")),
                            code);
                    return new ClickStats(total == null ? 0 : total, top);
                }
            }
            """;

    private static final String CONCURRENT_CLICKS_TEST = """
            package com.agentic.urlshortener;

            import static org.junit.jupiter.api.Assertions.assertEquals;

            import com.agentic.urlshortener.repository.ClicksRepository;
            import com.agentic.urlshortener.repository.Database;
            import com.agentic.urlshortener.repository.UrlsRepository;
            import java.nio.file.Files;
            import java.nio.file.Path;
            import java.util.ArrayList;
            import java.util.List;
            import java.util.concurrent.ExecutorService;
            import java.util.concurrent.Executors;
            import java.util.concurrent.Future;
            import java.util.concurrent.TimeUnit;
            import javax.sql.DataSource;
            import org.junit.jupiter.api.Test;

            class ConcurrentClicksTest {

                @Test
                void concurrentClicksAreNotLost() throws Exception {
                    Path tmp = Files.createTempDirectory("concurrent-clicks-test");
                    String dbPath = tmp.resolve("t.db").toString();
                    DataSource setupDs = Database.create(dbPath);
                    Database.runMigrations(setupDs);
                    String code = new UrlsRepository(setupDs).create("https://example.com").code();

                    ExecutorService pool = Executors.newFixedThreadPool(20);
                    try {
                        List<Future<?>> futures = new ArrayList<>();
                        for (int i = 0; i < 50; i++) {
                            futures.add(pool.submit(() -> {
                                DataSource ds = Database.create(dbPath);
                                new ClicksRepository(ds).recordClick(code, null, "junit");
                                return null;
                            }));
                        }
                        for (Future<?> future : futures) {
                            future.get(30, TimeUnit.SECONDS);
                        }
                    } finally {
                        pool.shutdownNow();
                    }

                    int finalCount = new UrlsRepository(Database.create(dbPath)).get(code).clickCount();
                    assertEquals(50, finalCount);
                }
            }
            """;
}
