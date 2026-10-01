package com.driftwatch.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Configuration contract: the shipped {@code application.yml} binds, and invalid values fail
 * startup with an error that names the offending key.
 */
class ConfigurationValidationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(TestConfig.class);

    @Configuration
    @EnableConfigurationProperties(DriftwatchProperties.class)
    static class TestConfig {}

    @Test
    void shippedConfigurationBindsAndValidates() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            DriftwatchProperties props = context.getBean(DriftwatchProperties.class);
            assertThat(props.metrics().windowSize()).isEqualTo(Duration.ofMinutes(1));
            assertThat(props.metrics().grace()).isEqualTo(Duration.ofMinutes(10));
            assertThat(props.detector().late().githubThreshold()).isEqualTo(Duration.ofHours(8));
            assertThat(props.security().requireStrongCredentials()).isFalse();
            assertThat(props.source().github().enabled()).isFalse();
            assertThat(props.streams().enabled()).isTrue();
        });
    }

    @Test
    void selfhostProfileRequiresStrongCredentialsAndEnablesTheGithubSource() {
        runner.withPropertyValues("spring.profiles.active=selfhost").run(context -> {
            assertThat(context).hasNotFailed();
            DriftwatchProperties props = context.getBean(DriftwatchProperties.class);
            assertThat(props.security().requireStrongCredentials()).isTrue();
            assertThat(props.source().github().enabled()).isTrue();
        });
    }

    @Test
    void loadProfileKeepsTheGithubSourceDisabled() {
        runner.withPropertyValues("spring.profiles.active=load").run(context -> {
            assertThat(context).hasNotFailed();
            DriftwatchProperties props = context.getBean(DriftwatchProperties.class);
            assertThat(props.source().github().enabled()).isFalse();
            assertThat(props.security().requireStrongCredentials()).isFalse();
        });
    }

    @Test
    void nullSpikeThresholdAboveOneFailsAndNamesTheKey() {
        runner.withPropertyValues("driftwatch.detector.null-spike.threshold=1.5").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasStackTraceContaining("detector.nullSpike.threshold");
        });
    }

    @Test
    void retentionShorterThanGracePlusBaselineFailsAndNamesTheKey() {
        runner.withPropertyValues("driftwatch.metrics.state-retention=PT5M").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasStackTraceContaining("driftwatch.metrics.state-retention");
        });
    }

    @Test
    void anomalyHistoryBeyondBaselineWindowsFailsAndNamesTheKey() {
        runner.withPropertyValues("driftwatch.detector.anomaly-spike.min-history-windows=4").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasStackTraceContaining("driftwatch.detector.anomaly-spike.min-history-windows");
        });
    }

    @Test
    void invertedFieldRangeFailsAndNamesTheKey() {
        runner.withPropertyValues(
                        "driftwatch.detector.field-range.fields.price.min=100",
                        "driftwatch.detector.field-range.fields.price.max=10")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("driftwatch.detector.field-range.fields.price");
                });
    }

    @Test
    void nonPositiveWindowFailsAndNamesTheKey() {
        runner.withPropertyValues("driftwatch.metrics.window-size=PT0S").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasStackTraceContaining("driftwatch.metrics.window-size");
        });
    }

    @Test
    void staleSourceThresholdOrderingFailsAndNamesTheKey() {
        runner.withPropertyValues("driftwatch.source-health.rss-stale-after=PT1M").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasStackTraceContaining("driftwatch.source-health");
        });
    }
}
