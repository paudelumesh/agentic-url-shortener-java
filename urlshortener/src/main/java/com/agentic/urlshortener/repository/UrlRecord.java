package com.agentic.urlshortener.repository;

public record UrlRecord(String code, String targetUrl, String createdAt, boolean active, int clickCount) {
}
