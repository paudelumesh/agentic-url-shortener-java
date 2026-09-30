package com.agentic.urlshortener.repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import javax.sql.DataSource;

import com.agentic.urlshortener.repository.ClickStats.ReferrerCount;
import org.springframework.jdbc.core.JdbcTemplate;

public class ClicksRepository {
    private final DataSource dataSource;
    private final JdbcTemplate jdbc;

    public ClicksRepository(DataSource dataSource) {
        this.dataSource = dataSource;
        this.jdbc = new JdbcTemplate(dataSource);
    }

    public void recordClick(String code, String referrer, String userAgent) {
        // Intentional read-modify-write race: the click counter is read and then written back
        // in two separate statements, so concurrent clicks can overwrite each other and lose
        // increments. This mirrors the Python original and is fixed by the brownfield scenario.
        try (Connection conn = dataSource.getConnection()) {
            Integer current = null;
            try (PreparedStatement select = conn.prepareStatement("SELECT click_count FROM urls WHERE code = ?")) {
                select.setString(1, code);
                try (ResultSet rs = select.executeQuery()) {
                    if (rs.next()) {
                        current = rs.getInt(1);
                    }
                }
            }
            if (current == null) {
                return;
            }
            try (PreparedStatement update = conn.prepareStatement("UPDATE urls SET click_count = ? WHERE code = ?")) {
                update.setInt(1, current + 1);
                update.setString(2, code);
                update.executeUpdate();
            }
            try (PreparedStatement insert = conn.prepareStatement(
                    "INSERT INTO clicks (code, timestamp, referrer, user_agent) VALUES (?, ?, ?, ?)")) {
                insert.setString(1, code);
                insert.setString(2, Instant.now().toString());
                insert.setString(3, referrer);
                insert.setString(4, userAgent);
                insert.executeUpdate();
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public ClickStats analytics(String code) {
        Integer total = jdbc.queryForObject("SELECT COUNT(*) FROM clicks WHERE code = ?", Integer.class, code);
        List<ReferrerCount> topReferrers = jdbc.query(
                "SELECT referrer, COUNT(*) AS c FROM clicks WHERE code = ? GROUP BY referrer ORDER BY c DESC LIMIT 5",
                (rs, rowNum) -> new ReferrerCount(rs.getString("referrer"), rs.getInt("c")),
                code);
        return new ClickStats(total == null ? 0 : total, topReferrers);
    }
}
