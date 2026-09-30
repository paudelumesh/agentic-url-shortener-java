package com.agentic.urlshortener.web;

import com.agentic.urlshortener.config.AppConfig;
import com.agentic.urlshortener.domain.UrlValidator;
import com.agentic.urlshortener.repository.UrlRecord;
import com.agentic.urlshortener.repository.UrlsRepository;
import com.agentic.urlshortener.web.dto.CreateUrlRequest;
import com.agentic.urlshortener.web.dto.UrlResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/urls")
public class UrlController {
    private final UrlsRepository urls;

    public UrlController(UrlsRepository urls) {
        this.urls = urls;
    }

    @PostMapping("")
    public ResponseEntity<UrlResponse> create(@RequestBody CreateUrlRequest payload) {
        try {
            UrlValidator.validateUrl(payload.url());
        } catch (IllegalArgumentException ex) {
            throw new ApiException(422, ex.getMessage());
        }
        return ResponseEntity.status(201).body(toResponse(urls.create(payload.url())));
    }

    @GetMapping("/{code}")
    public UrlResponse get(@PathVariable String code) {
        UrlRecord record = urls.get(code);
        if (record == null) {
            throw new ApiException(404, "code not found");
        }
        return toResponse(record);
    }

    @DeleteMapping("/{code}")
    public ResponseEntity<Void> delete(@PathVariable String code) {
        if (!urls.softDelete(code)) {
            throw new ApiException(404, "code not found");
        }
        return ResponseEntity.noContent().build();
    }

    private UrlResponse toResponse(UrlRecord record) {
        return new UrlResponse(
                record.code(),
                record.targetUrl(),
                AppConfig.BASE_URL + "/" + record.code(),
                record.createdAt(),
                record.active(),
                record.clickCount());
    }
}
