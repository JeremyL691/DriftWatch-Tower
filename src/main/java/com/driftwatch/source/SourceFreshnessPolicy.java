package com.driftwatch.source;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;

@Component
public class SourceFreshnessPolicy {

    private final Duration defaultStaleAfter;
    private final Duration rssStaleAfter;
    private final Duration pipelineStaleAfter;

    public SourceFreshnessPolicy(
            @Value("${driftwatch.source-health.default-stale-after:PT5M}") Duration defaultStaleAfter,
            @Value("${driftwatch.source-health.rss-stale-after:PT30M}") Duration rssStaleAfter,
            @Value("${driftwatch.source-health.pipeline-stale-after:PT24H}") Duration pipelineStaleAfter) {
        this.defaultStaleAfter = defaultStaleAfter;
        this.rssStaleAfter = rssStaleAfter;
        this.pipelineStaleAfter = pipelineStaleAfter;
    }

    public Duration staleAfter(String source, String eventType) {
        String haystack = (source + " " + eventType).toLowerCase(Locale.ROOT);
        if (haystack.contains("rss") || haystack.contains("coindesk")) {
            return rssStaleAfter;
        }
        if (haystack.contains("document") || haystack.contains("pipeline") || haystack.contains("sourcehero")) {
            return pipelineStaleAfter;
        }
        return defaultStaleAfter;
    }
}
