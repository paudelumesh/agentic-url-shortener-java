package com.agentic.urlshortener.repository;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;

import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

public final class Database {

    private Database() {
    }

    public static DataSource create(String dbPath) {
        SQLiteConfig config = new SQLiteConfig();
        config.setBusyTimeout(5000);
        config.setJournalMode(SQLiteConfig.JournalMode.WAL);
        config.enforceForeignKeys(true);
        SQLiteDataSource dataSource = new SQLiteDataSource(config);
        dataSource.setUrl("jdbc:sqlite:" + dbPath);
        return dataSource;
    }

    public static List<String> runMigrations(DataSource dataSource) {
        List<String> appliedNow = new ArrayList<>();
        try (Connection conn = dataSource.getConnection()) {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("CREATE TABLE IF NOT EXISTS schema_migrations (filename TEXT PRIMARY KEY, applied_at TEXT)");
            }
            Set<String> appliedAlready = new HashSet<>();
            try (Statement stmt = conn.createStatement();
                    ResultSet rs = stmt.executeQuery("SELECT filename FROM schema_migrations")) {
                while (rs.next()) {
                    appliedAlready.add(rs.getString(1));
                }
            }
            Resource[] resources = new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:db/migration/*.sql");
            Arrays.sort(resources,
                    Comparator.comparing(Resource::getFilename, Comparator.nullsFirst(String::compareTo)));
            for (Resource resource : resources) {
                String filename = resource.getFilename();
                if (filename == null || appliedAlready.contains(filename)) {
                    continue;
                }
                String script;
                try (InputStream in = resource.getInputStream()) {
                    script = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
                try (Statement stmt = conn.createStatement()) {
                    for (String part : script.split(";")) {
                        if (!part.isBlank()) {
                            stmt.execute(part);
                        }
                    }
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO schema_migrations (filename, applied_at) VALUES (?, datetime('now'))")) {
                    ps.setString(1, filename);
                    ps.executeUpdate();
                }
                appliedNow.add(filename);
            }
            return appliedNow;
        } catch (IOException | SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
