package com.agentic.urlshortener.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;

class CodeGeneratorTest {

    @Test
    void generateCodeHasExpectedLength() {
        String code = CodeGenerator.generateCode(c -> false, 7, 10);
        assertEquals(7, code.length());
    }

    @Test
    void generateCodeUsesBase62Alphabet() {
        String code = CodeGenerator.generateCode(c -> false, 7, 10);
        assertTrue(code.chars().allMatch(Character::isLetterOrDigit));
    }

    @Test
    void generateCodeRetriesOnCollision() {
        Set<String> seen = Set.of("AAAAAAA");
        String code = CodeGenerator.generateCode(seen::contains, 7, 10);
        assertNotEquals("AAAAAAA", code);
    }

    @Test
    void generateCodeRaisesAfterMaxAttempts() {
        assertThrows(IllegalStateException.class, () -> CodeGenerator.generateCode(c -> true, 7, 5));
    }
}
