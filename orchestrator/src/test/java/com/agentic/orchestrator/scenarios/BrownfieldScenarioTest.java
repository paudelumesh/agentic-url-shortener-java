package com.agentic.orchestrator.scenarios;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import com.agentic.orchestrator.Engine;
import com.agentic.orchestrator.RunState;
import com.agentic.orchestrator.RunStore;
import com.agentic.orchestrator.StageStatus;
import com.agentic.orchestrator.Workflow;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BrownfieldScenarioTest {

    @TempDir
    Path tmp;

    private Path copySource() throws Exception {
        Path repoRoot = Paths.get(System.getProperty("user.dir")).getParent();
        return ScenarioCommon.copyUrlShortenerSource(repoRoot, tmp.resolve("target"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void brownfieldScenarioFixesConcurrencyBug() throws Exception {
        Path target = copySource();
        Workflow workflow = BrownfieldScenario.build(target);
        Engine engine = new Engine(workflow, new RunStore(target.resolve("runs")));
        RunState state = engine.start("brownfield-test");

        assertEquals(StageStatus.PASSED, state.nodeStatus.get("design"));
        List<String> impacted = (List<String>) state.context.latest("design").outputs.get("impacted_files");
        assertTrue(impacted.stream().anyMatch(f -> f.contains("ClicksRepository.java")),
                "expected ClicksRepository.java in " + impacted);

        assertEquals(StageStatus.PASSED, state.nodeStatus.get("implementation"));
        assertEquals(StageStatus.PASSED, state.nodeStatus.get("unit_tests"));
        assertEquals(StageStatus.AWAITING_APPROVAL, state.nodeStatus.get("release_readiness"));

        String fixedTest = Files.readString(target.resolve(
                "urlshortener/src/test/java/com/agentic/urlshortener/ConcurrentClicksTest.java"));
        assertFalse(fixedTest.contains("Disabled"));
    }
}
