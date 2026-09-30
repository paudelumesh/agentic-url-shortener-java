package com.agentic.urlshortener.web;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class RestExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, String>> handle(ApiException ex) {
        return ResponseEntity.status(ex.status).body(Map.of("detail", ex.getMessage()));
    }
}
