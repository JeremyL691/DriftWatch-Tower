package com.driftwatch.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Self-host credentials must fail fast when missing or weak, and the error must not echo the
 * value. Tests and local development keep lightweight credentials because they run with
 * {@code require-strong-credentials=false}.
 */
class ProductionCredentialsValidatorTest {

    @Test
    void weakPasswordFailsWithoutEchoingTheValue() {
        String weak = "replace-with-a-strong-random-value";
        DriftwatchProperties props = properties("driftwatch", weak, List.of(), true);

        assertThatThrownBy(() -> new ProductionCredentialsValidator(props))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("driftwatch.security.admin.password")
                .hasMessageNotContaining(weak);
    }

    @Test
    void missingPasswordFails() {
        DriftwatchProperties props = properties("driftwatch", "", List.of(), true);

        assertThatThrownBy(() -> new ProductionCredentialsValidator(props))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("driftwatch.security.admin.password");
    }

    @Test
    void shortIngestTokenFails() {
        DriftwatchProperties props = properties("driftwatch", "a-very-long-admin-password", List.of("short"), true);

        assertThatThrownBy(() -> new ProductionCredentialsValidator(props))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("driftwatch.security.ingest-tokens");
    }

    @Test
    void strongCredentialsPass() {
        DriftwatchProperties props = properties("driftwatch",
                "S3cure-selfhost-password-value",
                List.of("ingest-token-0123456789"),
                true);

        new ProductionCredentialsValidator(props);
    }

    @Test
    void relaxedModeAllowsDevelopmentCredentials() {
        DriftwatchProperties props = properties("driftwatch", "dev", List.of(), false);

        new ProductionCredentialsValidator(props);
    }

    @Test
    void blankPasswordFailsEvenWithoutStrictMode() {
        DriftwatchProperties props = properties("driftwatch", " ", List.of(), false);

        assertThatThrownBy(() -> new ProductionCredentialsValidator(props))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("driftwatch.security.admin.password");
    }

    private static DriftwatchProperties properties(String username,
                                                   String password,
                                                   List<String> ingestTokens,
                                                   boolean requireStrong) {
        DriftwatchProperties defaults = new DriftwatchProperties(
                new DriftwatchProperties.Detector(
                        new DriftwatchProperties.Detector.Duplicate(Duration.ofMinutes(5)),
                        new DriftwatchProperties.Detector.Late(Duration.ofMinutes(5), Duration.ofHours(8)),
                        new DriftwatchProperties.Detector.NullSpike(0.6, 3),
                        new DriftwatchProperties.Detector.AnomalySpike(2, 2, 3.0, 5),
                        new DriftwatchProperties.Detector.FieldRange(java.util.Map.of()),
                        new DriftwatchProperties.Detector.FieldFormat("")),
                new DriftwatchProperties.Metrics(Duration.ofMinutes(1), Duration.ofMinutes(10),
                        Duration.ofMinutes(2), Duration.ofMinutes(15)),
                new DriftwatchProperties.Streams(true),
                new DriftwatchProperties.SourceHealth(Duration.ofMinutes(5), Duration.ofMinutes(30),
                        Duration.ofHours(24), Duration.ofMinutes(15)),
                new DriftwatchProperties.Security(
                        new DriftwatchProperties.Security.Admin(username, password), ingestTokens, requireStrong),
                new DriftwatchProperties.Source(new DriftwatchProperties.Source.Github(false)),
                new DriftwatchProperties.Bridge(false, "raw-events", "", 10000, ""));
        return defaults;
    }
}
