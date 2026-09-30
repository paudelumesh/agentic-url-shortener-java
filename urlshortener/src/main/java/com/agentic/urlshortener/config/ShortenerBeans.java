package com.agentic.urlshortener.config;

import javax.sql.DataSource;

import com.agentic.urlshortener.domain.TokenBucketRateLimiter;
import com.agentic.urlshortener.repository.ClicksRepository;
import com.agentic.urlshortener.repository.Database;
import com.agentic.urlshortener.repository.UrlsRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ShortenerBeans {

    @Bean
    public DataSource dataSource(@Value("${app.db-path:urlshortener.db}") String dbPath) {
        DataSource dataSource = Database.create(dbPath);
        Database.runMigrations(dataSource);
        return dataSource;
    }

    @Bean
    public TokenBucketRateLimiter tokenBucketRateLimiter(
            @Value("${app.rate-limit-capacity:60}") int capacity,
            @Value("${app.rate-limit-refill-per-second:1.0}") double refillPerSecond) {
        return new TokenBucketRateLimiter(capacity, refillPerSecond);
    }

    @Bean
    public UrlsRepository urlsRepository(DataSource dataSource) {
        return new UrlsRepository(dataSource);
    }

    @Bean
    public ClicksRepository clicksRepository(DataSource dataSource) {
        return new ClicksRepository(dataSource);
    }
}
