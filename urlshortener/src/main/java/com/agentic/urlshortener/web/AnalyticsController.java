package com.agentic.urlshortener.web;

import com.agentic.urlshortener.repository.ClickStats;
import com.agentic.urlshortener.repository.ClicksRepository;
import com.agentic.urlshortener.repository.UrlsRepository;
import com.agentic.urlshortener.web.dto.AnalyticsResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/urls")
public class AnalyticsController {
    private final UrlsRepository urls;
    private final ClicksRepository clicks;

    public AnalyticsController(UrlsRepository urls, ClicksRepository clicks) {
        this.urls = urls;
        this.clicks = clicks;
    }

    @GetMapping("/{code}/analytics")
    public AnalyticsResponse analytics(@PathVariable String code) {
        if (urls.get(code) == null) {
            throw new ApiException(404, "code not found");
        }
        ClickStats stats = clicks.analytics(code);
        return new AnalyticsResponse(stats.totalClicks(), stats.topReferrers());
    }
}
