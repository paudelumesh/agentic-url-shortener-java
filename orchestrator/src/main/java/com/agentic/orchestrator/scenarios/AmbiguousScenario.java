package com.agentic.orchestrator.scenarios;

import com.agentic.orchestrator.ContextEntry;
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

public final class AmbiguousScenario {
    private AmbiguousScenario() {
    }

    public static Workflow build(Path targetDir) {
        if (targetDir == null) {
            throw new IllegalArgumentException(
                    "target_dir is required — pass a disposable workspace copy "
                            + "(see ScenarioCommon.copyUrlShortenerSource), never the real repo");
        }
        StageNode requirements = StageNode.builder("requirements", new RequirementsExecutor())
                .requiresApproval(true).build();
        StageNode design = StageNode.builder("design", new DesignExecutor())
                .dependsOn("requirements").build();
        StageNode implementation = StageNode.builder("implementation", new ImplementationExecutor(targetDir))
                .dependsOn("design").build();
        StageNode unitTests = StageNode.builder("unit_tests",
                        new ScenarioCommon.RunMavenTestsExecutor(targetDir, "SecurityHardeningTest"))
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
        return new Workflow("ambiguous",
                List.of(requirements, design, implementation, unitTests, documentation, releaseReadiness));
    }

    static class RequirementsExecutor extends StageExecutor {
        RequirementsExecutor() {
            name = "ambiguous_security_requirements";
        }

        @Override
        public StageResult run(RunContext context) {
            Map<String, Object> outputs = new LinkedHashMap<>();
            outputs.put("raw_requirement", "Make it more secure.");
            outputs.put("interpretation",
                    "\"More secure\" is not actionable as stated. Normalized into three "
                            + "concrete, independently verifiable sub-requirements based on the "
                            + "service's current threat exposure.");
            outputs.put("assumptions", List.of(
                    "Rate limiting (already implemented) is in scope for confirmation, not redesign.",
                    "\"Secure\" does not include authentication/user accounts — out of scope, flagged as a limitation.",
                    "A static bearer token is an acceptable stand-in for real per-user auth in this prototype."));
            outputs.put("open_questions", List.of(
                    "Should DELETE ownership be per-user in a future iteration, not a single shared token?",
                    "Should the target-URL denylist also block DNS names that resolve to internal IPs at "
                            + "request time (full SSRF protection), or is a static host/IP-literal check sufficient for now?"));
            outputs.put("sub_requirements", List.of(
                    "Confirm rate limiting is wired to POST /api/urls and GET /{code} (already true).",
                    "Reject shortening targets that point at loopback/private/link-local hosts "
                            + "(open-redirect/SSRF-lite guard).",
                    "Require a bearer token header on DELETE /api/urls/{code}."));
            return new StageResult(StageStatus.PASSED, outputs,
                    "Normalized ambiguous requirement 'make it more secure' into 3 concrete "
                            + "sub-requirements; human approval required before design proceeds.");
        }
    }

    static class DesignExecutor extends StageExecutor {
        DesignExecutor() {
            name = "ambiguous_design";
        }

        @Override
        public StageResult run(RunContext context) {
            ContextEntry approved = context.latest("requirements");
            Object subReqs = approved != null ? approved.outputs.getOrDefault("sub_requirements", List.of()) : List.of();
            Map<String, Object> outputs = new LinkedHashMap<>();
            outputs.put("task_plan", List.of(
                    "Extend UrlValidator: reject loopback/private/link-local target hosts.",
                    "Add OWNER_TOKEN to AppConfig.",
                    "Require X-Owner-Token header on DELETE /api/urls/{code}, else 403."));
            outputs.put("approved_sub_requirements", subReqs);
            outputs.put("risks", List.of("Static shared token is a stopgap, not real per-user authorization."));
            return new StageResult(StageStatus.PASSED, outputs,
                    "Design derived from the human-approved normalized requirement.");
        }
    }

    static class ImplementationExecutor extends StageExecutor {
        private final Path targetDir;

        ImplementationExecutor(Path targetDir) {
            name = "ambiguous_implementation";
            this.targetDir = targetDir;
        }

        @Override
        public StageResult run(RunContext context) {
            try {
                String base = "urlshortener/src/main/java/com/agentic/urlshortener/";
                write(base + "domain/UrlValidator.java", URL_VALIDATOR_V3);
                write(base + "config/AppConfig.java", APP_CONFIG_V2);
                write(base + "web/UrlController.java", URL_CONTROLLER_SECURE);
                write("urlshortener/src/test/java/com/agentic/urlshortener/SecurityHardeningTest.java",
                        SECURITY_HARDENING_TEST);
            } catch (IOException e) {
                return new StageResult(StageStatus.FAILED, Map.of(),
                        "failed to write scenario files: " + e.getMessage());
            }
            Map<String, Object> outputs = new LinkedHashMap<>();
            outputs.put("files_changed", List.of(
                    "urlshortener/src/main/java/com/agentic/urlshortener/domain/UrlValidator.java",
                    "urlshortener/src/main/java/com/agentic/urlshortener/config/AppConfig.java",
                    "urlshortener/src/main/java/com/agentic/urlshortener/web/UrlController.java",
                    "urlshortener/src/test/java/com/agentic/urlshortener/SecurityHardeningTest.java"));
            outputs.put("schema_change", "none");
            outputs.put("new_dependencies", List.of());
            outputs.put("diff_preview", "reject loopback/private targets; require X-Owner-Token on DELETE");
            return new StageResult(StageStatus.PASSED, outputs,
                    "Implemented the 3 approved sub-requirements.");
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
            name = "ambiguous_documentation";
            this.targetDir = targetDir;
        }

        @Override
        public StageResult run(RunContext context) {
            try {
                Path docsDir = targetDir.resolve("docs");
                Files.createDirectories(docsDir);
                Path docPath = docsDir.resolve("api-changes.md");
                String existing = Files.exists(docPath) ? Files.readString(docPath) : "# API Changes\n";
                String section = "\n## Security hardening (ambiguous scenario)\n\n"
                        + "The raw requirement \"make it more secure\" was normalized (with human approval) "
                        + "into three sub-requirements: confirmed rate limiting coverage, an open-redirect/"
                        + "SSRF-lite guard rejecting loopback/private/link-local shorten targets, and a "
                        + "bearer-token check (`X-Owner-Token`) on `DELETE /api/urls/{code}`. Per-user "
                        + "authentication remains out of scope.\n";
                Files.writeString(docPath, existing + section);
            } catch (IOException e) {
                return new StageResult(StageStatus.FAILED, Map.of(),
                        "failed to write documentation: " + e.getMessage());
            }
            return new StageResult(StageStatus.PASSED,
                    Map.of("files_changed", List.of("docs/api-changes.md")),
                    "Documented the security hardening changes and their limitations.");
        }
    }

    private static final String URL_VALIDATOR_V3 = """
            package com.agentic.urlshortener.domain;

            import java.net.InetAddress;
            import java.net.URI;
            import java.net.URISyntaxException;
            import java.net.UnknownHostException;
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
                    String host = uri.getHost();
                    if (host == null || host.isEmpty()) {
                        throw new IllegalArgumentException("url must include a host");
                    }
                    if (host.equalsIgnoreCase("localhost")) {
                        throw new IllegalArgumentException("url host is not allowed (internal/loopback address)");
                    }
                    if (host.matches("[0-9a-fA-F:.]+")) {
                        try {
                            InetAddress addr = InetAddress.getByName(host);
                            if (addr.isLoopbackAddress() || addr.isSiteLocalAddress()
                                    || addr.isLinkLocalAddress() || addr.isAnyLocalAddress()) {
                                throw new IllegalArgumentException(
                                        "url host is not allowed (internal/loopback address)");
                            }
                        } catch (UnknownHostException ignored) {
                            // not an IP literal after all; nothing to check
                        }
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

    private static final String APP_CONFIG_V2 = """
            package com.agentic.urlshortener.config;

            public final class AppConfig {
                public static final int CODE_LENGTH = 7;
                public static final int RATE_LIMIT_CAPACITY = 60;
                public static final double RATE_LIMIT_REFILL_PER_SECOND = 1.0;
                public static final String BASE_URL = "http://localhost:8000";
                public static final String OWNER_TOKEN = "change-me-in-production";  // prototype-only static token

                private AppConfig() {
                }
            }
            """;

    private static final String URL_CONTROLLER_SECURE = """
            package com.agentic.urlshortener.web;

            import com.agentic.urlshortener.config.AppConfig;
            import com.agentic.urlshortener.domain.UrlValidator;
            import com.agentic.urlshortener.repository.UrlRecord;
            import com.agentic.urlshortener.repository.UrlsRepository;
            import com.agentic.urlshortener.web.dto.CreateUrlRequest;
            import com.agentic.urlshortener.web.dto.UrlResponse;
            import java.nio.charset.StandardCharsets;
            import java.security.MessageDigest;
            import org.springframework.http.HttpStatus;
            import org.springframework.http.ResponseEntity;
            import org.springframework.web.bind.annotation.DeleteMapping;
            import org.springframework.web.bind.annotation.GetMapping;
            import org.springframework.web.bind.annotation.PathVariable;
            import org.springframework.web.bind.annotation.PostMapping;
            import org.springframework.web.bind.annotation.RequestBody;
            import org.springframework.web.bind.annotation.RequestHeader;
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
                    } catch (IllegalArgumentException e) {
                        throw new ApiException(422, e.getMessage());
                    }
                    UrlRecord row = repo.create(req.url());
                    return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(row));
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
                public ResponseEntity<Void> delete(@PathVariable String code,
                        @RequestHeader(value = "X-Owner-Token", required = false) String token) {
                    if (token == null || !MessageDigest.isEqual(
                            token.getBytes(StandardCharsets.UTF_8),
                            AppConfig.OWNER_TOKEN.getBytes(StandardCharsets.UTF_8))) {
                        throw new ApiException(403, "missing or invalid owner token");
                    }
                    if (!repo.softDelete(code)) {
                        throw new ApiException(404, "code not found");
                    }
                    return ResponseEntity.noContent().build();
                }

                private UrlResponse toResponse(UrlRecord row) {
                    return new UrlResponse(row.code(), row.targetUrl(), AppConfig.BASE_URL + "/" + row.code(),
                            row.createdAt(), row.active(), row.clickCount());
                }
            }
            """;

    private static final String SECURITY_HARDENING_TEST = """
            package com.agentic.urlshortener;

            import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
            import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
            import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
            import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

            import com.agentic.urlshortener.config.AppConfig;
            import com.fasterxml.jackson.databind.ObjectMapper;
            import java.nio.file.Files;
            import java.nio.file.Path;
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
            import org.springframework.test.web.servlet.MvcResult;

            @SpringBootTest
            @AutoConfigureMockMvc
            class SecurityHardeningTest {
                static final Path TMP_DIR;

                static {
                    try {
                        TMP_DIR = Files.createTempDirectory("security-hardening-test");
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
                void createRejectsLoopbackTarget() throws Exception {
                    mockMvc.perform(post("/api/urls").contentType(MediaType.APPLICATION_JSON)
                                    .content("{\\"url\\":\\"http://127.0.0.1/admin\\"}"))
                            .andExpect(status().isUnprocessableEntity());
                }

                @Test
                void createRejectsLocalhostTarget() throws Exception {
                    mockMvc.perform(post("/api/urls").contentType(MediaType.APPLICATION_JSON)
                                    .content("{\\"url\\":\\"http://localhost/admin\\"}"))
                            .andExpect(status().isUnprocessableEntity());
                }

                @Test
                void createAcceptsPublicTarget() throws Exception {
                    mockMvc.perform(post("/api/urls").contentType(MediaType.APPLICATION_JSON)
                                    .content("{\\"url\\":\\"https://example.com\\"}"))
                            .andExpect(status().isCreated());
                }

                @Test
                void deleteWithoutTokenIsForbidden() throws Exception {
                    String code = createCode();
                    mockMvc.perform(delete("/api/urls/" + code))
                            .andExpect(status().isForbidden());
                }

                @Test
                void deleteWithCorrectTokenSucceeds() throws Exception {
                    String code = createCode();
                    mockMvc.perform(delete("/api/urls/" + code)
                                    .header("X-Owner-Token", AppConfig.OWNER_TOKEN))
                            .andExpect(status().isNoContent());
                }

                private String createCode() throws Exception {
                    MvcResult result = mockMvc.perform(post("/api/urls").contentType(MediaType.APPLICATION_JSON)
                                    .content("{\\"url\\":\\"https://example.com\\"}"))
                            .andExpect(status().isCreated())
                            .andExpect(jsonPath("$.code").exists())
                            .andReturn();
                    return new ObjectMapper()
                            .readTree(result.getResponse().getContentAsString())
                            .get("code").asText();
                }
            }
            """;
}
