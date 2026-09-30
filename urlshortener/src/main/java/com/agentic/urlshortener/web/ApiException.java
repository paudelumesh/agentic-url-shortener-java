package com.agentic.urlshortener.web;

public class ApiException extends RuntimeException {
    public final int status;

    public ApiException(int status, String detail) {
        super(detail);
        this.status = status;
    }
}
