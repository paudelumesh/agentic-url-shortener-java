package com.agentic.urlshortener.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClicksRepositoryTest {

    @TempDir
    Path tmp;

    UrlsRepository urls;
    ClicksRepository clicks;

    @BeforeEach
    void setUp() {
        DataSource dataSource = Database.create(tmp.resolve("t.db").toString());
        Database.runMigrations(dataSource);
        urls = new UrlsRepository(dataSource);
        clicks = new ClicksRepository(dataSource);
    }

    @Test
    void recordClickIncrementsCount() {
        UrlRecord row = urls.create("https://example.com");
        clicks.recordClick(row.code(), "google.com", "junit");
        assertEquals(1, urls.get(row.code()).clickCount());
    }

    @Test
    void analyticsReportsTotalAndReferrers() {
        UrlRecord row = urls.create("https://example.com");
        clicks.recordClick(row.code(), "google.com", "junit");
        clicks.recordClick(row.code(), "google.com", "junit");
        clicks.recordClick(row.code(), "direct", "junit");
        ClickStats stats = clicks.analytics(row.code());
        assertEquals(3, stats.totalClicks());
        assertEquals(new ClickStats.ReferrerCount("google.com", 2), stats.topReferrers().get(0));
    }
}
