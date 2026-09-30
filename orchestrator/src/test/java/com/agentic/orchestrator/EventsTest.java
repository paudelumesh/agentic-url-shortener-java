package com.agentic.orchestrator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EventsTest {

    @TempDir
    Path tmp;

    @Test
    void appendAndReadAllRoundtrip() {
        EventLog log = new EventLog(tmp.resolve("run-1").resolve("events.jsonl"));
        log.append(new Event("run-1", "a", "pending", "running", "t1", 1, ""));
        log.append(new Event("run-1", "a", "running", "passed", "t2", 1, ""));
        List<Event> events = log.readAll();
        assertEquals(List.of("running", "passed"), events.stream().map(e -> e.toState).toList());
    }

    @Test
    void readAllReturnsEmptyListForMissingFile() {
        EventLog log = new EventLog(tmp.resolve("run-1").resolve("events.jsonl"));
        assertTrue(log.readAll().isEmpty());
    }
}
