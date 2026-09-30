package com.agentic.urlshortener.repository;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

class DatabaseTest {

    @TempDir
    Path tmp;

    @Test
    void runMigrationsCreatesTables() {
        DataSource dataSource = Database.create(tmp.resolve("test.db").toString());
        List<String> applied = Database.runMigrations(dataSource);
        assertTrue(applied.contains("V1__init.sql"));
        List<String> tables = new JdbcTemplate(dataSource)
                .queryForList("SELECT name FROM sqlite_master WHERE type='table'", String.class);
        assertTrue(tables.containsAll(List.of("urls", "clicks", "schema_migrations")));
    }

    @Test
    void runMigrationsIsIdempotent() {
        DataSource dataSource = Database.create(tmp.resolve("test.db").toString());
        Database.runMigrations(dataSource);
        assertTrue(Database.runMigrations(dataSource).isEmpty());
    }
}
