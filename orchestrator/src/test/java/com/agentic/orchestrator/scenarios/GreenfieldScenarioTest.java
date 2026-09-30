package com.agentic.orchestrator.scenarios;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

import com.agentic.orchestrator.Engine;
import com.agentic.orchestrator.RunState;
import com.agentic.orchestrator.RunStore;
import com.agentic.orchestrator.StageStatus;
import com.agentic.orchestrator.Workflow;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GreenfieldScenarioTest {

    @TempDir
    Path tmp;

    private Path copySource() throws Exception {
        Path repoRoot = Paths.get(System.getProperty("user.dir")).getParent();
        return ScenarioCommon.copyUrlShortenerSource(repoRoot, tmp.resolve("target"));
    }

    @Test
    void greenfieldScenarioAddsAliasAndExpiry() throws Exception {
        Path target = copySource();
        Workflow workflow = GreenfieldScenario.build(target);
        Engine engine = new Engine(workflow, new RunStore(target.resolve("runs")));
        RunState state = engine.start("greenfield-test");

        assertEquals(StageStatus.PASSED, state.nodeStatus.get("implementation"));
        assertEquals(StageStatus.PASSED, state.nodeStatus.get("unit_tests"));
        assertEquals(StageStatus.PASSED, state.nodeStatus.get("documentation"));
        assertEquals(StageStatus.AWAITING_APPROVAL, state.nodeStatus.get("release_readiness"));
        assertTrue(Files.exists(target.resolve(
                "urlshortener/src/test/java/com/agentic/urlshortener/AliasAndExpiryTest.java")));

        RunState approved = engine.approve("greenfield-test", "release_readiness", "looks good");
        assertEquals(StageStatus.PASSED, approved.nodeStatus.get("release_readiness"));
    }

    @Test
    void greenfieldImplementationPassesFullUrlshortenerSuite() throws Exception {
        Path target = copySource();
        Workflow workflow = GreenfieldScenario.build(target);
        new Engine(workflow, new RunStore(target.resolve("runs"))).start("greenfield-test-2");

        Process process = new ProcessBuilder("mvn", "-B", "test", "-pl", "urlshortener")
                .directory(target.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(30, TimeUnit.MINUTES), "mvn timed out");
        assertEquals(0, process.exitValue(), output);
    }
}
