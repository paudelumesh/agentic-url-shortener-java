package com.agentic.urlshortener.repository;

import java.time.Instant;
import java.util.List;
import javax.sql.DataSource;

import com.agentic.urlshortener.domain.CodeGenerator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

public class UrlsRepository {
    private static final RowMapper<UrlRecord> ROW_MAPPER = (rs, rowNum) -> new UrlRecord(
            rs.getString("code"),
            rs.getString("target_url"),
            rs.getString("created_at"),
            rs.getInt("active") == 1,
            rs.getInt("click_count"));

    private final JdbcTemplate jdbc;

    public UrlsRepository(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    public boolean exists(String code) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM urls WHERE code = ?", Integer.class, code);
        return count != null && count > 0;
    }

    public UrlRecord create(String targetUrl) {
        String code = CodeGenerator.generateCode(this::exists);
        jdbc.update(
                "INSERT INTO urls (code, target_url, created_at, active, click_count) VALUES (?, ?, ?, 1, 0)",
                code, targetUrl, Instant.now().toString());
        return get(code);
    }

    public UrlRecord get(String code) {
        List<UrlRecord> rows = jdbc.query(
                "SELECT code, target_url, created_at, active, click_count FROM urls WHERE code = ?",
                ROW_MAPPER, code);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public boolean softDelete(String code) {
        return jdbc.update("UPDATE urls SET active = 0 WHERE code = ? AND active = 1", code) > 0;
    }
}
