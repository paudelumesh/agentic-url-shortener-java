package com.agentic.urlshortener.domain;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class UrlValidatorTest {

    @ParameterizedTest
    @ValueSource(strings = {"https://example.com", "http://example.com/path?x=1"})
    void validateUrlAcceptsValidUrls(String url) {
        assertDoesNotThrow(() -> UrlValidator.validateUrl(url));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not-a-url", "ftp://example.com", "javascript:alert(1)"})
    void validateUrlRejectsInvalidUrls(String url) {
        assertThrows(IllegalArgumentException.class, () -> UrlValidator.validateUrl(url));
    }
}
