package com.agentic.urlshortener.web.dto;

public record UrlResponse(String code, String targetUrl, String shortUrl, String createdAt, boolean active,
        int clickCount) {
}
