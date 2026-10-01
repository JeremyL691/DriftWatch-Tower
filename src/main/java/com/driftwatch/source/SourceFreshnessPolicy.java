package com.driftwatch.source;

import com.driftwatch.config.DriftwatchProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;

@Component
public class SourceFreshnessPolicy {

    private final Duration defaultStaleAfter;
    private final Duration rssStaleAfter;
    private final Duration pipelineStaleAfter;

    @Autowired
    public SourceFreshnessPolicy(DriftwatchProperties properties) {
        this(properties.sourceHealth().defaultStaleAfter(),
                properties.sourceHealth().rssStaleAfter(),
                properties.sourceHealth().pipelineStaleAfter());
    }

    public SourceFreshnessPolicy(Duration defaultStaleAfter,
                                 Duration rssStaleAfter,
                                 Duration pipelineStaleAfter) {
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
