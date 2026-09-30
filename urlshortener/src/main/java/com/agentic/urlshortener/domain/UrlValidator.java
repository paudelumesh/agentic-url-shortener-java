package com.agentic.urlshortener.domain;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Set;

public final class UrlValidator {
    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

    private UrlValidator() {
    }

    public static void validateUrl(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("url must not be empty");
        }
        final URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("url must include a host", e);
        }
        String scheme = uri.getScheme();
        if (scheme == null || !ALLOWED_SCHEMES.contains(scheme)) {
            throw new IllegalArgumentException("url scheme must be one of [http, https]");
        }
        if (uri.getHost() == null) {
            throw new IllegalArgumentException("url must include a host");
        }
    }
}
