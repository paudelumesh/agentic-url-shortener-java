package com.agentic.urlshortener.web.dto;

import java.util.List;

import com.agentic.urlshortener.repository.ClickStats.ReferrerCount;

public record AnalyticsResponse(int totalClicks, List<ReferrerCount> topReferrers) {
}
