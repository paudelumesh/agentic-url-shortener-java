package com.agentic.urlshortener.web;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
abstract class BaseApiTest {

    static final Path TMP;

    static {
        try {
            TMP = Files.createTempDirectory("api-test");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected JdbcTemplate jdbc;

    @DynamicPropertySource
    static void testProperties(DynamicPropertyRegistry registry) {
        registry.add("app.db-path", () -> TMP.resolve("t.db").toString());
        registry.add("app.rate-limit-capacity", () -> "100000");
    }

    @BeforeEach
    void cleanDatabase() {
        jdbc.update("DELETE FROM clicks");
        jdbc.update("DELETE FROM urls");
    }
}
