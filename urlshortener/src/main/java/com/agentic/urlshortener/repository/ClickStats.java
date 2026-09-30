package com.agentic.urlshortener.repository;

import java.util.List;

public record ClickStats(int totalClicks, List<ReferrerCount> topReferrers) {

    public record ReferrerCount(String referrer, int count) {
    }
}
