package com.agentic.urlshortener.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UrlsRepositoryTest {

    @TempDir
    Path tmp;

    UrlsRepository repo;

    @BeforeEach
    void setUp() {
        DataSource dataSource = Database.create(tmp.resolve("t.db").toString());
        Database.runMigrations(dataSource);
        repo = new UrlsRepository(dataSource);
    }

    @Test
    void createReturnsRowWithGeneratedCode() {
        UrlRecord row = repo.create("https://example.com");
        assertEquals(7, row.code().length());
        assertEquals("https://example.com", row.targetUrl());
        assertTrue(row.active());
    }

    @Test
    void getReturnsCreatedRow() {
        UrlRecord created = repo.create("https://example.com");
        assertEquals("https://example.com", repo.get(created.code()).targetUrl());
    }

    @Test
    void getReturnsNullForMissingCode() {
        assertNull(repo.get("nope"));
    }

    @Test
    void softDeleteMarksInactive() {
        UrlRecord created = repo.create("https://example.com");
        assertTrue(repo.softDelete(created.code()));
        assertFalse(repo.get(created.code()).active());
    }

    @Test
    void softDeleteReturnsFalseForMissingCode() {
        assertFalse(repo.softDelete("nope"));
    }
}
