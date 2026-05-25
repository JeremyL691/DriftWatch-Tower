package com.driftwatch.source;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class SourceFreshnessPolicyTest {

    private final SourceFreshnessPolicy policy = new SourceFreshnessPolicy(
            Duration.ofMinutes(5),
            Duration.ofMinutes(30),
            Duration.ofHours(24));

    @Test
    void marketSourcesUseDefaultThreshold() {
        assertThat(policy.staleAfter("binance", "market_tick")).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void rssSourcesUseRssThreshold() {
        assertThat(policy.staleAfter("coindesk", "rss_article")).isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    void pipelineSourcesUseLongerThreshold() {
        assertThat(policy.staleAfter("sourcehero", "document_ingestion")).isEqualTo(Duration.ofHours(24));
    }
}
