package com.agentic.orchestrator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliTest {

    @TempDir
    Path tmp;

    private record CliResult(int exitCode, String stdout, String stderr) {
    }

    private CliResult runCli(String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(System.getProperty("java.home") + "/bin/java");
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add("com.agentic.orchestrator.cli.OrchestrateCli");
        Collections.addAll(command, args);
        Process process = new ProcessBuilder(command).directory(tmp.toFile()).start();
        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(120, TimeUnit.SECONDS), "CLI timed out: " + String.join(" ", command));
        return new CliResult(process.exitValue(), stdout, stderr);
    }

    private Path workspace() throws Exception {
        Path ws = tmp.resolve("workspace");
        Files.createDirectories(ws);
        return ws;
    }

    @Test
    void runStatusRejectMetricsRoundTrip() throws Exception {
        CliResult run = runCli("run", "ambiguous", "--run-id", "test-run",
                "--target-dir", workspace().toString());
        assertEquals(0, run.exitCode(), run.stderr());
        assertTrue(run.stdout().contains("awaiting_approval"), run.stdout());

        CliResult status = runCli("status", "test-run");
        assertEquals(0, status.exitCode(), status.stderr());
        assertTrue(status.stdout().contains("test-run"), status.stdout());

        CliResult reject = runCli("reject", "test-run", "requirements",
                "--note", "redo", "--revise", "requirements");
        assertEquals(0, reject.exitCode(), reject.stderr());
        assertTrue(reject.stdout().contains("awaiting_approval"), reject.stdout());

        CliResult metrics = runCli("metrics");
        assertEquals(0, metrics.exitCode(), metrics.stderr());
        JsonNode report = new ObjectMapper().readTree(metrics.stdout());
        assertEquals(1, report.get("runs").asInt());
    }

    @Test
    void runRejectsUnknownScenario() throws Exception {
        CliResult result = runCli("run", "does-not-exist");
        assertNotEquals(0, result.exitCode());
        assertTrue(result.stderr().contains("unknown scenario"), result.stderr());
    }

    @Test
    void statusUnknownRunIdExitsCleanlyWithoutStackTrace() throws Exception {
        CliResult result = runCli("status", "does-not-exist-run-id");
        assertNotEquals(0, result.exitCode());
        assertFalse(result.stderr().contains("\tat "),
                "expected a clean CLI error, not a stack trace: " + result.stderr());
    }

    @Test
    void approveNodeNotAwaitingApprovalExitsCleanlyWithoutStackTrace() throws Exception {
        CliResult run = runCli("run", "ambiguous", "--run-id", "test-run-3",
                "--target-dir", workspace().toString());
        assertEquals(0, run.exitCode(), run.stderr());

        CliResult approve = runCli("approve", "test-run-3", "design", "--note", "x");
        assertNotEquals(0, approve.exitCode());
        assertTrue(approve.stderr().contains("not awaiting approval"), approve.stderr());
        assertFalse(approve.stderr().contains("\tat "),
                "expected a clean CLI error, not a stack trace: " + approve.stderr());
    }

    @Test
    void runWithTargetDirPersistsAcrossStop() throws Exception {
        CliResult run = runCli("run", "ambiguous", "--run-id", "targeted-run",
                "--target-dir", workspace().toString());
        assertEquals(0, run.exitCode(), run.stderr());

        CliResult stop = runCli("stop", "targeted-run");
        assertEquals(0, stop.exitCode(), stop.stderr());
        assertTrue(stop.stdout().contains("targeted-run"), stop.stdout());
    }
}
