package com.agentic.orchestrator.scenarios;

import com.agentic.orchestrator.Workflow;

import java.nio.file.Path;
import java.util.Map;
import java.util.function.Function;

public final class ScenarioRegistry {
    private ScenarioRegistry() {
    }

    public static final Map<String, Function<Path, Workflow>> REGISTRY = Map.of(
            "greenfield", GreenfieldScenario::build,
            "brownfield", BrownfieldScenario::build,
            "ambiguous", AmbiguousScenario::build);
}
