package com.agentic.orchestrator;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

public class EventLog {
    private final Path path;

    public EventLog(Path path) {
        this.path = path;
    }

    public synchronized void append(Event e) {
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (BufferedWriter w = Files.newBufferedWriter(path, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                w.write(Json.writeValueAsString(e));
                w.newLine();
            }
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    public List<Event> readAll() {
        List<Event> events = new ArrayList<>();
        if (!Files.exists(path)) {
            return events;
        }
        try (BufferedReader r = Files.newBufferedReader(path)) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.strip();
                if (!line.isEmpty()) {
                    events.add(Json.readValue(line, Event.class));
                }
            }
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        return events;
    }
}
