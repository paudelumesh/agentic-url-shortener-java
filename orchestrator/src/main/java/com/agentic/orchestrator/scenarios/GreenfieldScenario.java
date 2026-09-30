package com.agentic.orchestrator.scenarios;

import com.agentic.orchestrator.Guardrails;
import com.agentic.orchestrator.Policy;
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

public final class GreenfieldScenario {
    private GreenfieldScenario() {
    }

    public static Workflow build(Path targetDir) {
        if (targetDir == null) {
            throw new IllegalArgumentException(
                    "target_dir is required — pass a disposable workspace copy "
                            + "(see ScenarioCommon.copyUrlShortenerSource), never the real repo");
        }
        StageNode requirements = StageNode.builder("requirements", new RequirementsExecutor()).build();
        StageNode design = StageNode.builder("design", new DesignExecutor())
                .dependsOn("requirements").build();
        StageNode implementation = StageNode.builder("implementation", new ImplementationExecutor(targetDir))
                .dependsOn("design").build();
        StageNode unitTests = StageNode.builder("unit_tests",
                        new ScenarioCommon.RunMavenTestsExecutor(targetDir, "AliasAndExpiryTest"))
                .dependsOn("implementation").build();
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
        return new Workflow("greenfield",
                List.of(requirements, design, implementation, unitTests, documentation, releaseReadiness));
    }

    static class RequirementsExecutor extends StageExecutor {
        RequirementsExecutor() {
            name = "greenfield_requirements";
        }

        @Override
        public StageResult run(RunContext context) {
            Map<String, Object> outputs = new LinkedHashMap<>();
            outputs.put("problem", "Users want to choose their own short code and set an optional expiry.");
            outputs.put("assumptions", List.of(
                    "Custom aliases are case-sensitive and must be alphanumeric, 3-32 chars.",
                    "Expiry is measured in seconds from creation, capped at 1 year."));
            outputs.put("acceptance_criteria", List.of(
                    "POST /api/urls accepts optional custom_alias and expires_in_seconds.",
                    "A taken alias returns 409.",
                    "An expired link returns 410 on redirect."));
            return new StageResult(StageStatus.PASSED, outputs,
                    "Normalized greenfield requirement: custom alias + expiry.");
        }
    }

    static class DesignExecutor extends StageExecutor {
        DesignExecutor() {
            name = "greenfield_design";
        }

        @Override
        public StageResult run(RunContext context) {
            Map<String, Object> outputs = new LinkedHashMap<>();
            outputs.put("task_plan", List.of(
                    "Add nullable expires_at column via migration V3.",
                    "Extend UrlValidator with validateAlias/validateExpiry.",
                    "Extend UrlsRepository.create to accept code/expires_at.",
                    "Extend CreateUrlRequest/UrlResponse DTOs.",
                    "Extend UrlController to pass through new fields, handle 409/422.",
                    "Extend RedirectController to 410 on expiry."));
            outputs.put("risks", List.of("Alias collisions with previously auto-generated codes."));
            return new StageResult(StageStatus.PASSED, outputs,
                    "Task plan for custom alias + expiry.");
        }
    }

    static class ImplementationExecutor extends StageExecutor {
        private final Path targetDir;

        ImplementationExecutor(Path targetDir) {
            name = "greenfield_implementation";
            this.targetDir = targetDir;
        }

        @Override
        public StageResult run(RunContext context) {
            try {
                String base = "urlshortener/src/main/java/com/agentic/urlshortener/";
                write(base + "domain/UrlValidator.java", URL_VALIDATOR_V2);
                write("urlshortener/src/main/resources/db/migration/V3__add_expiry.sql", V3_SQL);
                write(base + "repository/UrlRecord.java", URL_RECORD_V2);
                write(base + "repository/DuplicateCodeException.java", DUPLICATE_CODE_EXCEPTION);
                write(base + "repository/UrlsRepository.java", URLS_REPOSITORY_V2);
                write(base + "web/dto/CreateUrlRequest.java", CREATE_URL_REQUEST_V2);
                write(base + "web/dto/UrlResponse.java", URL_RESPONSE_V2);
                write(base + "web/UrlController.java", URL_CONTROLLER_V2);
                write(base + "web/RedirectController.java", REDIRECT_CONTROLLER_V2);
                write("urlshortener/src/test/java/com/agentic/urlshortener/AliasAndExpiryTest.java",
                        ALIAS_AND_EXPIRY_TEST);
            } catch (IOException e) {
                return new StageResult(StageStatus.FAILED, Map.of(),
                        "failed to write scenario files: " + e.getMessage());
            }
            Map<String, Object> outputs = new LinkedHashMap<>();
            outputs.put("files_changed", List.of(
                    "urlshortener/src/main/java/com/agentic/urlshortener/domain/UrlValidator.java",
                    "urlshortener/src/main/resources/db/migration/V3__add_expiry.sql",
                    "urlshortener/src/main/java/com/agentic/urlshortener/repository/UrlRecord.java",
                    "urlshortener/src/main/java/com/agentic/urlshortener/repository/DuplicateCodeException.java",
                    "urlshortener/src/main/java/com/agentic/urlshortener/repository/UrlsRepository.java",
                    "urlshortener/src/main/java/com/agentic/urlshortener/web/dto/CreateUrlRequest.java",
                    "urlshortener/src/main/java/com/agentic/urlshortener/web/dto/UrlResponse.java",
                    "urlshortener/src/main/java/com/agentic/urlshortener/web/UrlController.java",
                    "urlshortener/src/main/java/com/agentic/urlshortener/web/RedirectController.java",
                    "urlshortener/src/test/java/com/agentic/urlshortener/AliasAndExpiryTest.java"));
            outputs.put("schema_change", "additive");
            outputs.put("diff_preview", "ALTER TABLE urls ADD COLUMN expires_at TEXT;");
            return new StageResult(StageStatus.PASSED, outputs,
                    "Added custom_alias and expires_in_seconds support end-to-end.");
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
            name = "greenfield_documentation";
            this.targetDir = targetDir;
        }

        @Override
        public StageResult run(RunContext context) {
            try {
                Path docsDir = targetDir.resolve("docs");
                Files.createDirectories(docsDir);
                Path docPath = docsDir.resolve("api-changes.md");
                String existing = Files.exists(docPath) ? Files.readString(docPath) : "# API Changes\n";
                String section = "\n## Custom alias and expiry (greenfield scenario)\n\n"
                        + "`POST /api/urls` now accepts optional `custom_alias` (3-32 alphanumeric chars) "
                        + "and `expires_in_seconds` (1 to 31536000). A taken alias returns 409. "
                        + "An expired link returns 410 on redirect.\n";
                Files.writeString(docPath, existing + section);
            } catch (IOException e) {
                return new StageResult(StageStatus.FAILED, Map.of(),
                        "failed to write documentation: " + e.getMessage());
            }
            return new StageResult(StageStatus.PASSED,
                    Map.of("files_changed", List.of("docs/api-changes.md")),
                    "Documented custom alias and expiry fields.");
        }
    }

    private static final String URL_VALIDATOR_V2 = """
            package com.agentic.urlshortener.domain;

            import java.net.URI;
            import java.net.URISyntaxException;
            import java.util.Set;

            public final class UrlValidator {
                public static final int ALIAS_MIN_LENGTH = 3;
                public static final int ALIAS_MAX_LENGTH = 32;
                public static final long MAX_EXPIRY_SECONDS = 31536000L;

                private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

                private UrlValidator() {
                }

                public static void validateUrl(String url) {
                    if (url == null || url.isBlank()) {
                        throw new IllegalArgumentException("url must not be empty");
                    }
                    URI uri;
                    try {
                        uri = new URI(url);
                    } catch (URISyntaxException e) {
                        throw new IllegalArgumentException("url is not a valid URI: " + e.getMessage());
                    }
                    if (uri.getScheme() == null || !ALLOWED_SCHEMES.contains(uri.getScheme().toLowerCase())) {
                        throw new IllegalArgumentException("url scheme must be one of [http, https]");
                    }
                    if (uri.getHost() == null || uri.getHost().isEmpty()) {
                        throw new IllegalArgumentException("url must include a host");
                    }
                }

                public static void validateAlias(String alias) {
                    if (alias == null || alias.length() < ALIAS_MIN_LENGTH || alias.length() > ALIAS_MAX_LENGTH) {
                        throw new IllegalArgumentException(
                                "alias must be between " + ALIAS_MIN_LENGTH + " and " + ALIAS_MAX_LENGTH + " characters");
                    }
                    if (!alias.chars().allMatch(Character::isLetterOrDigit)) {
                        throw new IllegalArgumentException("alias must be alphanumeric");
                    }
                }

                public static void validateExpiry(long expiresInSeconds) {
                    if (expiresInSeconds <= 0) {
                        throw new IllegalArgumentException("expires_in_seconds must be positive");
                    }
                    if (expiresInSeconds > MAX_EXPIRY_SECONDS) {
                        throw new IllegalArgumentException(
                                "expires_in_seconds must not exceed " + MAX_EXPIRY_SECONDS);
                    }
                }
            }
            """;

    private static final String V3_SQL = """
            ALTER TABLE urls ADD COLUMN expires_at TEXT;
            """;

    private static final String URL_RECORD_V2 = """
            package com.agentic.urlshortener.repository;

            public record UrlRecord(String code, String targetUrl, String createdAt, boolean active, int clickCount,
                    String expiresAt) {
            }
            """;

    private static final String DUPLICATE_CODE_EXCEPTION = """
            package com.agentic.urlshortener.repository;

            public class DuplicateCodeException extends RuntimeException {
                public DuplicateCodeException(String m) {
                    super(m);
                }
            }
            """;

    private static final String URLS_REPOSITORY_V2 = """
            package com.agentic.urlshortener.repository;

            import com.agentic.urlshortener.config.AppConfig;
            import java.security.SecureRandom;
            import java.time.Instant;
            import java.util.List;
            import javax.sql.DataSource;
            import org.springframework.jdbc.core.JdbcTemplate;

            public class UrlsRepository {
                private static final String ALPHABET =
                        "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

                private final JdbcTemplate jdbc;
                private final SecureRandom random = new SecureRandom();

                public UrlsRepository(DataSource dataSource) {
                    this.jdbc = new JdbcTemplate(dataSource);
                }

                public boolean exists(String code) {
                    Integer n = jdbc.queryForObject(
                            "SELECT COUNT(*) FROM urls WHERE code = ?", Integer.class, code);
                    return n != null && n > 0;
                }

                public UrlRecord create(String targetUrl) {
                    return create(targetUrl, null, null);
                }

                public UrlRecord create(String targetUrl, String code, String expiresAt) {
                    if (code != null) {
                        if (exists(code)) {
                            throw new DuplicateCodeException("alias '" + code + "' is already taken");
                        }
                    } else {
                        code = generateUniqueCode();
                    }
                    String createdAt = Instant.now().toString();
                    jdbc.update("INSERT INTO urls (code, target_url, created_at, active, click_count, expires_at) "
                            + "VALUES (?, ?, ?, 1, 0, ?)", code, targetUrl, createdAt, expiresAt);
                    return get(code);
                }

                public UrlRecord get(String code) {
                    List<UrlRecord> rows = jdbc.query(
                            "SELECT code, target_url, created_at, active, click_count, expires_at FROM urls WHERE code = ?",
                            (rs, i) -> new UrlRecord(rs.getString("code"), rs.getString("target_url"),
                                    rs.getString("created_at"), rs.getBoolean("active"),
                                    rs.getInt("click_count"), rs.getString("expires_at")),
                            code);
                    return rows.isEmpty() ? null : rows.get(0);
                }

                public boolean softDelete(String code) {
                    return jdbc.update("UPDATE urls SET active = 0 WHERE code = ? AND active = 1", code) > 0;
                }

                private String generateUniqueCode() {
                    String code;
                    do {
                        StringBuilder sb = new StringBuilder(AppConfig.CODE_LENGTH);
                        for (int i = 0; i < AppConfig.CODE_LENGTH; i++) {
                            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
                        }
                        code = sb.toString();
                    } while (exists(code));
                    return code;
                }
            }
            """;

    private static final String CREATE_URL_REQUEST_V2 = """
            package com.agentic.urlshortener.web.dto;

            public record CreateUrlRequest(String url, String customAlias, Long expiresInSeconds) {
            }
            """;

    private static final String URL_RESPONSE_V2 = """
            package com.agentic.urlshortener.web.dto;

            public record UrlResponse(String code, String targetUrl, String shortUrl, String createdAt, boolean active,
                    int clickCount, String expiresAt) {
            }
            """;

    private static final String URL_CONTROLLER_V2 = """
            package com.agentic.urlshortener.web;

            import com.agentic.urlshortener.config.AppConfig;
            import com.agentic.urlshortener.domain.UrlValidator;
            import com.agentic.urlshortener.repository.DuplicateCodeException;
            import com.agentic.urlshortener.repository.UrlRecord;
            import com.agentic.urlshortener.repository.UrlsRepository;
            import com.agentic.urlshortener.web.dto.CreateUrlRequest;
            import com.agentic.urlshortener.web.dto.UrlResponse;
            import java.time.Instant;
            import org.springframework.http.HttpStatus;
            import org.springframework.http.ResponseEntity;
            import org.springframework.web.bind.annotation.DeleteMapping;
            import org.springframework.web.bind.annotation.GetMapping;
            import org.springframework.web.bind.annotation.PathVariable;
            import org.springframework.web.bind.annotation.PostMapping;
            import org.springframework.web.bind.annotation.RequestBody;
            import org.springframework.web.bind.annotation.RequestMapping;
            import org.springframework.web.bind.annotation.RestController;

            @RestController
            @RequestMapping("/api/urls")
            public class UrlController {
                private final UrlsRepository repo;

                public UrlController(UrlsRepository repo) {
                    this.repo = repo;
                }

                @PostMapping
                public ResponseEntity<UrlResponse> create(@RequestBody CreateUrlRequest req) {
                    try {
                        UrlValidator.validateUrl(req.url());
                        if (req.customAlias() != null) {
                            UrlValidator.validateAlias(req.customAlias());
                        }
                        if (req.expiresInSeconds() != null) {
                            UrlValidator.validateExpiry(req.expiresInSeconds());
                        }
                    } catch (IllegalArgumentException e) {
                        throw new ApiException(422, e.getMessage());
                    }
                    String expiresAt = null;
                    if (req.expiresInSeconds() != null) {
                        expiresAt = Instant.now().plusSeconds(req.expiresInSeconds()).toString();
                    }
                    try {
                        UrlRecord row = repo.create(req.url(), req.customAlias(), expiresAt);
                        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(row));
                    } catch (DuplicateCodeException e) {
                        throw new ApiException(409, e.getMessage());
                    }
                }

                @GetMapping("/{code}")
                public UrlResponse get(@PathVariable String code) {
                    UrlRecord row = repo.get(code);
                    if (row == null) {
                        throw new ApiException(404, "code not found");
                    }
                    return toResponse(row);
                }

                @DeleteMapping("/{code}")
                public ResponseEntity<Void> delete(@PathVariable String code) {
                    if (!repo.softDelete(code)) {
                        throw new ApiException(404, "code not found");
                    }
                    return ResponseEntity.noContent().build();
                }

                private UrlResponse toResponse(UrlRecord row) {
                    return new UrlResponse(row.code(), row.targetUrl(), AppConfig.BASE_URL + "/" + row.code(),
                            row.createdAt(), row.active(), row.clickCount(), row.expiresAt());
                }
            }
            """;

    private static final String REDIRECT_CONTROLLER_V2 = """
            package com.agentic.urlshortener.web;

            import com.agentic.urlshortener.repository.ClicksRepository;
            import com.agentic.urlshortener.repository.UrlRecord;
            import com.agentic.urlshortener.repository.UrlsRepository;
            import java.time.Instant;
            import org.springframework.http.HttpStatus;
            import org.springframework.http.ResponseEntity;
            import org.springframework.web.bind.annotation.GetMapping;
            import org.springframework.web.bind.annotation.PathVariable;
            import org.springframework.web.bind.annotation.RequestHeader;
            import org.springframework.web.bind.annotation.RestController;

            @RestController
            public class RedirectController {
                private final UrlsRepository urlsRepo;
                private final ClicksRepository clicksRepo;

                public RedirectController(UrlsRepository urlsRepo, ClicksRepository clicksRepo) {
                    this.urlsRepo = urlsRepo;
                    this.clicksRepo = clicksRepo;
                }

                static boolean isExpired(UrlRecord row) {
                    String expiresAt = row.expiresAt();
                    if (expiresAt == null || expiresAt.isBlank()) {
                        return false;
                    }
                    return Instant.parse(expiresAt).compareTo(Instant.now()) <= 0;
                }

                @GetMapping("/{code}")
                public ResponseEntity<Void> redirect(@PathVariable String code,
                        @RequestHeader(value = "Referer", required = false) String referrer,
                        @RequestHeader(value = "User-Agent", required = false) String userAgent) {
                    UrlRecord row = urlsRepo.get(code);
                    if (row == null) {
                        throw new ApiException(404, "code not found");
                    }
                    if (!row.active() || isExpired(row)) {
                        throw new ApiException(410, "link has been deleted or expired");
                    }
                    clicksRepo.recordClick(code, referrer, userAgent);
                    return ResponseEntity.status(HttpStatus.FOUND).header("Location", row.targetUrl()).build();
                }
            }
            """;

    private static final String ALIAS_AND_EXPIRY_TEST = """
            package com.agentic.urlshortener;

            import static org.hamcrest.Matchers.notNullValue;
            import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
            import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
            import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
            import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

            import java.nio.file.Files;
            import java.nio.file.Path;
            import java.time.Instant;
            import javax.sql.DataSource;
            import org.junit.jupiter.api.BeforeEach;
            import org.junit.jupiter.api.Test;
            import org.springframework.beans.factory.annotation.Autowired;
            import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
            import org.springframework.boot.test.context.SpringBootTest;
            import org.springframework.http.MediaType;
            import org.springframework.jdbc.core.JdbcTemplate;
            import org.springframework.test.context.DynamicPropertyRegistry;
            import org.springframework.test.context.DynamicPropertySource;
            import org.springframework.test.web.servlet.MockMvc;

            @SpringBootTest
            @AutoConfigureMockMvc
            class AliasAndExpiryTest {
                static final Path TMP_DIR;

                static {
                    try {
                        TMP_DIR = Files.createTempDirectory("alias-expiry-test");
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }

                @DynamicPropertySource
                static void properties(DynamicPropertyRegistry registry) {
                    registry.add("app.db-path", () -> TMP_DIR.resolve("t.db").toString());
                    registry.add("app.rate-limit-capacity", () -> "100000");
                }

                @Autowired
                MockMvc mockMvc;

                @Autowired
                DataSource dataSource;

                private JdbcTemplate jdbc;

                @BeforeEach
                void clean() {
                    jdbc = new JdbcTemplate(dataSource);
                    jdbc.update("DELETE FROM clicks");
                    jdbc.update("DELETE FROM urls");
                }

                @Test
                void createWithCustomAlias() throws Exception {
                    mockMvc.perform(post("/api/urls").contentType(MediaType.APPLICATION_JSON)
                                    .content("{\\"url\\":\\"https://example.com\\",\\"custom_alias\\":\\"mylink\\"}"))
                            .andExpect(status().isCreated())
                            .andExpect(jsonPath("$.code").value("mylink"));
                }

                @Test
                void createWithDuplicateAliasReturns409() throws Exception {
                    String body = "{\\"url\\":\\"https://example.com\\",\\"custom_alias\\":\\"dup\\"}";
                    mockMvc.perform(post("/api/urls").contentType(MediaType.APPLICATION_JSON).content(body))
                            .andExpect(status().isCreated());
                    mockMvc.perform(post("/api/urls").contentType(MediaType.APPLICATION_JSON).content(body))
                            .andExpect(status().isConflict());
                }

                @Test
                void createWithInvalidAliasReturns422() throws Exception {
                    mockMvc.perform(post("/api/urls").contentType(MediaType.APPLICATION_JSON)
                                    .content("{\\"url\\":\\"https://example.com\\",\\"custom_alias\\":\\"a\\"}"))
                            .andExpect(status().isUnprocessableEntity());
                }

                @Test
                void createWithExpirySetsExpiresAt() throws Exception {
                    mockMvc.perform(post("/api/urls").contentType(MediaType.APPLICATION_JSON)
                                    .content("{\\"url\\":\\"https://example.com\\",\\"expires_in_seconds\\":3600}"))
                            .andExpect(status().isCreated())
                            .andExpect(jsonPath("$.expires_at", notNullValue()));
                }

                @Test
                void redirectReturns410WhenExpired() throws Exception {
                    jdbc.update("INSERT INTO urls (code, target_url, created_at, active, click_count, expires_at) "
                                    + "VALUES (?,?,?,?,?,?)",
                            "expired1", "https://example.com", Instant.now().toString(), 1, 0,
                            Instant.now().minusSeconds(60).toString());
                    mockMvc.perform(get("/expired1"))
                            .andExpect(status().is(410));
                }
            }
            """;
}
