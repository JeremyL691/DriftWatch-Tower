package com.driftwatch.source;

import com.driftwatch.config.DriftwatchProperties;
import com.driftwatch.persistence.CollectorStateRepository;
import com.driftwatch.persistence.SourceGapRepository;
import com.driftwatch.persistence.SourceOutboxRepository;
import com.driftwatch.persistence.SourcePollRunRepository;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.mock;

/** Builds the real collector status rules for tests that need the production lost threshold. */
public final class CollectorStatusFixtures {

    private CollectorStatusFixtures() {
    }

    static CollectorStatusService collectorStatus(Clock clock, Duration pollInterval) {
        return new CollectorStatusService(
                mock(CollectorStateRepository.class),
                mock(SourceOutboxRepository.class),
                mock(SourceGapRepository.class),
                mock(SourcePollRunRepository.class),
                properties(pollInterval),
                clock);
    }

    /** Production-shaped properties for the GitHub collector (repository apache/kafka, scheduler on). */
    public static DriftwatchProperties properties(Duration pollInterval) {
        return new DriftwatchProperties(
                new DriftwatchProperties.Detector(
                        new DriftwatchProperties.Detector.Duplicate(Duration.ofMinutes(5)),
                        new DriftwatchProperties.Detector.Late(Duration.ofMinutes(5), Duration.ofHours(8)),
                        new DriftwatchProperties.Detector.NullSpike(0.6, 3),
                        new DriftwatchProperties.Detector.AnomalySpike(2, 2, 3.0, 5),
                        new DriftwatchProperties.Detector.FieldRange(Map.of()),
                        new DriftwatchProperties.Detector.FieldFormat("")),
                new DriftwatchProperties.Metrics(Duration.ofMinutes(1), Duration.ofMinutes(10),
                        Duration.ofMinutes(2), Duration.ofMinutes(15)),
                new DriftwatchProperties.Streams(true),
                new DriftwatchProperties.SourceHealth(Duration.ofMinutes(5), Duration.ofMinutes(30),
                        Duration.ofHours(24), Duration.ofMinutes(15)),
                new DriftwatchProperties.Security(
                        new DriftwatchProperties.Security.Admin("u", "a-long-enough-password"), List.of(), false),
                new DriftwatchProperties.Source(new DriftwatchProperties.Source.Github(true, "apache/kafka",
                        "https://api.github.com", pollInterval, 3, 100, Duration.ofSeconds(5),
                        Duration.ofSeconds(20), 300, "", "1.0.0", false, true)),
                new DriftwatchProperties.Bridge(false, "raw-events", "", 10000, ""));
    }
}
