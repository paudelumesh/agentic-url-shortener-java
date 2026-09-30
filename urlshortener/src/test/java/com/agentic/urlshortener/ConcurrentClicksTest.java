package com.agentic.urlshortener;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import com.agentic.urlshortener.repository.ClicksRepository;
import com.agentic.urlshortener.repository.Database;
import com.agentic.urlshortener.repository.UrlsRepository;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

@Disabled("known read-modify-write race in ClicksRepository.recordClick; fixed by the brownfield scenario")
class ConcurrentClicksTest {

    @Test
    void concurrentClicksAreNotLost() throws Exception {
        Path tmp = Files.createTempDirectory("concurrent-clicks-test");
        String dbPath = tmp.resolve("t.db").toString();
        DataSource setupDs = Database.create(dbPath);
        Database.runMigrations(setupDs);
        String code = new UrlsRepository(setupDs).create("https://example.com").code();

        ExecutorService pool = Executors.newFixedThreadPool(20);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 50; i++) {
                futures.add(pool.submit(() -> {
                    DataSource ds = Database.create(dbPath);
                    new ClicksRepository(ds).recordClick(code, null, "junit");
                    return null;
                }));
            }
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        int finalCount = new UrlsRepository(Database.create(dbPath)).get(code).clickCount();
        assertEquals(50, finalCount);
    }
}
