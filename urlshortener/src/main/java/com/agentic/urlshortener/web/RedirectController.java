package com.agentic.urlshortener.web;

import com.agentic.urlshortener.repository.ClicksRepository;
import com.agentic.urlshortener.repository.UrlRecord;
import com.agentic.urlshortener.repository.UrlsRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RedirectController {
    private final UrlsRepository urls;
    private final ClicksRepository clicks;

    public RedirectController(UrlsRepository urls, ClicksRepository clicks) {
        this.urls = urls;
        this.clicks = clicks;
    }

    @GetMapping("/{code}")
    public ResponseEntity<Void> redirect(
            @PathVariable String code,
            @RequestHeader(value = "referer", required = false) String referer,
            @RequestHeader(value = "user-agent", required = false) String userAgent) {
        UrlRecord record = urls.get(code);
        if (record == null) {
            throw new ApiException(404, "code not found");
        }
        if (!record.active()) {
            throw new ApiException(410, "link has been deleted");
        }
        clicks.recordClick(code, referer, userAgent);
        return ResponseEntity.status(302).header("Location", record.targetUrl()).build();
    }
}
