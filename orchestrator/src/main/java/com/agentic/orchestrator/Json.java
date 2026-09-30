package com.agentic.orchestrator;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

public final class Json {
    private Json() {}

    public static final ObjectMapper MAPPER = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    public static ObjectWriter prettyWriter() {
        return MAPPER.writerWithDefaultPrettyPrinter();
    }

    public static <T> T readValue(String content, Class<T> type) {
        try {
            return MAPPER.readValue(content, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to parse JSON", e);
        }
    }

    public static String writeValueAsString(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to write JSON", e);
        }
    }
}
