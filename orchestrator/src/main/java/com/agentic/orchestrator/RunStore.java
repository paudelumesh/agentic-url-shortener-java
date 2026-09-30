package com.agentic.orchestrator;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.stream.Stream;

public class RunStore {
    private final Path baseDir;

    public RunStore(Path baseDir) {
        this.baseDir = baseDir;
        try {
            Files.createDirectories(baseDir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Path statePath(String runId) {
        return baseDir.resolve(runId).resolve("state.json");
    }

    public void save(RunState state) {
        Path path = statePath(state.runId);
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, Json.prettyWriter().writeValueAsString(state));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public RunState load(String runId) {
        Path path = statePath(runId);
        if (!Files.exists(path)) {
            throw new NoSuchElementException("unknown run id '" + runId + "'");
        }
        try {
            return Json.readValue(Files.readString(path), RunState.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public boolean exists(String runId) {
        return Files.exists(statePath(runId));
    }

    public List<String> listRuns() {
        if (!Files.exists(baseDir)) {
            return new ArrayList<>();
        }
        try (Stream<Path> stream = Files.list(baseDir)) {
            List<String> runs = new ArrayList<>();
            stream.filter(Files::isDirectory).map(p -> p.getFileName().toString()).forEach(runs::add);
            Collections.sort(runs);
            return runs;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Path eventsPath(String runId) {
        return baseDir.resolve(runId).resolve("events.jsonl");
    }
}
