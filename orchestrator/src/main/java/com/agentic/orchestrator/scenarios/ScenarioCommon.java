package com.agentic.orchestrator.scenarios;

import com.agentic.orchestrator.ContextEntry;
import com.agentic.orchestrator.RunContext;
import com.agentic.orchestrator.StageExecutor;
import com.agentic.orchestrator.StageResult;
import com.agentic.orchestrator.StageStatus;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ScenarioCommon {
    private ScenarioCommon() {
    }

    public static Path copyUrlShortenerSource(Path repoRoot, Path destDir) throws IOException {
        Files.createDirectories(destDir);
        copyRecursive(repoRoot.resolve("urlshortener"), destDir.resolve("urlshortener"));
        copyRecursive(repoRoot.resolve("orchestrator"), destDir.resolve("orchestrator"));
        Files.copy(repoRoot.resolve("pom.xml"), destDir.resolve("pom.xml"),
                StandardCopyOption.REPLACE_EXISTING);
        Path mvnDir = repoRoot.resolve(".mvn");
        if (Files.isDirectory(mvnDir)) {
            copyRecursive(mvnDir, destDir.resolve(".mvn"));
        }
        return destDir;
    }

    private static void copyRecursive(Path src, Path dst) throws IOException {
        Files.walkFileTree(src, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(dst.resolve(src.relativize(dir)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.copy(file, dst.resolve(src.relativize(file)), StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    public static List<String> grepImpactedFiles(Path targetDir, String pattern)
            throws IOException, InterruptedException {
        Path base = targetDir.toAbsolutePath().normalize();
        Process process = new ProcessBuilder("grep", "-rl", pattern,
                base.resolve("urlshortener/src").toString()).start();
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        process.waitFor();
        List<String> relative = new ArrayList<>();
        for (String line : out.split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            relative.add(base.relativize(Path.of(line).toAbsolutePath().normalize()).toString());
        }
        relative.sort(String::compareTo);
        return relative;
    }

    public static class RunMavenTestsExecutor extends StageExecutor {
        private static final Pattern SUMMARY = Pattern.compile(
                "Tests run: (\\d+), Failures: (\\d+), Errors: (\\d+), Skipped: (\\d+)");

        private final Path targetDir;
        private final String testClass;

        public RunMavenTestsExecutor(Path targetDir, String testClass) {
            name = "run_maven_tests";
            this.targetDir = targetDir;
            this.testClass = testClass;
        }

        @Override
        public StageResult run(RunContext context) {
            try {
                Process process = new ProcessBuilder("mvn", "-B", "test", "-pl", "urlshortener",
                                "-Dtest=" + testClass, "-Dsurefire.failIfNoSpecifiedTests=false")
                        .directory(targetDir.toFile())
                        .redirectErrorStream(true)
                        .start();
                String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                int exitCode = process.waitFor();

                int total = 0, failures = 0, errors = 0, skipped = 0;
                Matcher matcher = SUMMARY.matcher(output);
                while (matcher.find()) {
                    total = Integer.parseInt(matcher.group(1));
                    failures = Integer.parseInt(matcher.group(2));
                    errors = Integer.parseInt(matcher.group(3));
                    skipped = Integer.parseInt(matcher.group(4));
                }
                int passed = total - failures - errors - skipped;
                String notes = output.lines().filter(l -> !l.isBlank()).reduce((a, b) -> b).orElse("");

                Map<String, Object> outputs = new LinkedHashMap<>();
                outputs.put("tests_total", total);
                outputs.put("tests_passed", passed);
                StageStatus status = exitCode == 0 ? StageStatus.PASSED : StageStatus.FAILED;
                return new StageResult(status, outputs, notes);
            } catch (Exception e) {
                return new StageResult(StageStatus.FAILED, Map.of(),
                        "maven run failed: " + e.getMessage());
            }
        }
    }

    public static class ReleaseReadinessExecutor extends StageExecutor {
        public ReleaseReadinessExecutor() {
            name = "release_readiness";
        }

        @Override
        public StageResult run(RunContext context) {
            ContextEntry impl = context.latest("implementation");
            ContextEntry tests = context.latest("unit_tests");
            Map<String, Object> outputs = new LinkedHashMap<>();
            outputs.put("schema_change", impl != null ? impl.outputs.getOrDefault("schema_change", "none") : "none");
            outputs.put("tests_total", tests != null ? tests.outputs.getOrDefault("tests_total", 0) : 0);
            outputs.put("tests_passed", tests != null ? tests.outputs.getOrDefault("tests_passed", 0) : 0);
            outputs.put("diff_preview", impl != null ? impl.outputs.getOrDefault("diff_preview", "") : "");
            return new StageResult(StageStatus.PASSED, outputs, "Release readiness check aggregated.");
        }
    }
}
