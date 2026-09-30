package com.agentic.urlshortener.domain;

import java.security.SecureRandom;
import java.util.function.Predicate;

import com.agentic.urlshortener.config.AppConfig;

public final class CodeGenerator {
    private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private CodeGenerator() {
    }

    public static String generateCode(Predicate<String> exists) {
        return generateCode(exists, AppConfig.CODE_LENGTH, 10);
    }

    public static String generateCode(Predicate<String> exists, int length, int maxAttempts) {
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            StringBuilder candidate = new StringBuilder(length);
            for (int i = 0; i < length; i++) {
                candidate.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
            }
            if (!exists.test(candidate.toString())) {
                return candidate.toString();
            }
        }
        throw new IllegalStateException("could not generate a unique code after " + maxAttempts + " attempts");
    }
}
