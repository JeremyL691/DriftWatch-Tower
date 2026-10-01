package com.driftwatch.stream;

import com.driftwatch.config.KafkaTopics;
import com.driftwatch.event.DataEvent;
import com.driftwatch.event.PayloadHasher;
import com.driftwatch.event.RawEnvelope;
import com.driftwatch.quality.AlertType;
import com.driftwatch.quality.FieldFormatDetector;
import com.driftwatch.quality.FieldRangeDetector;
import com.driftwatch.quality.LateEventDetector;
import com.driftwatch.quality.RuleVersions;
import com.driftwatch.quality.ScopeKey;
import com.driftwatch.quality.schema.BaselineMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.TestInputTopic;
import org.apache.kafka.streams.TestOutputTopic;
import org.apache.kafka.streams.TopologyTestDriver;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Detection contract from the execution guide, sections 5.1-5.4.
 *
 * <p>The cases that used to reproduce missed detections (all-missing null spike, single-event
 * baseline anomaly spike, out-of-order window wipe) now assert the fixed behaviour, and the
 * remaining cases lock the rest of the contract: expired/future exclusion, per-scope
 * watermarks, canonical scope keys, redelivery/conflict identity handling, one alert per
 * window and the baseline WARMING_UP/BASELINE_ZERO evidence.
 */
class DetectionContractTest {

    private static final String SOURCE = "contract-source";
    private static final String NULL_TYPE = "contract_null_event";
    private static final Map<String, String> ASK_NUMBER = Map.of("ask", "NUMBER");
    private static final String ANOMALY_TYPE = "contract_anomaly_event";

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final StreamSerdes serdes = new StreamSerdes(objectMapper);
    private final PayloadHasher hasher = new PayloadHasher();

    // ---------------------------------------------------------------- guide 5.4 cases

    @Test
    void allMissingValuesProduceExactlyOneNullSpike() {
        try (Driver driver = driver(Map.of(NULL_TYPE, ASK_NUMBER))) {
            Instant windowStart = Instant.now().truncatedTo(ChronoUnit.MINUTES);
            for (int i = 0; i < 5; i++) {
                driver.pipe(new DataEvent("null-" + i, SOURCE, NULL_TYPE,
                        windowStart.plusSeconds(i), Map.of("bid", 100.0 + i)));
            }

            List<KeyValue<String, ProcessedEvent>> results = driver.output();
            assertThat(results).hasSize(5);

            List<ProcessedEvent.ProcessedAlert> nullAlerts = alertsOf(results, AlertType.NULL_SPIKE);
            assertThat(nullAlerts)
                    .as("the window must fire exactly once once total>=3 and null_rate>0.6")
                    .hasSize(1);
            ProcessedEvent.ProcessedAlert alert = nullAlerts.get(0);
            assertThat(alert.evidence().get("null_count").asLong()).isEqualTo(3);
            assertThat(alert.evidence().get("total_count").asLong()).isEqualTo(3);
            assertThat(alert.evidence().get("null_rate").asDouble()).isEqualTo(1.0);
            assertThat(alert.evidence().get("window_start").asText()).isEqualTo(windowStart.toString());
            assertThat(alert.evidence().get("rule_version").asText()).isEqualTo(RuleVersions.RULES_VERSION);
        }
    }

    @Test
    void normalThenTwoMissingStillProducesOneNullSpike() {
        try (Driver driver = driver(Map.of(NULL_TYPE, ASK_NUMBER))) {
            Instant windowStart = Instant.now().truncatedTo(ChronoUnit.MINUTES);
            driver.pipe(new DataEvent("old-0", SOURCE, NULL_TYPE, windowStart, Map.of("bid", 1.0, "ask", 2.0)));
            driver.pipe(new DataEvent("old-1", SOURCE, NULL_TYPE, windowStart.plusSeconds(1), Map.of("bid", 2.0)));
            driver.pipe(new DataEvent("old-2", SOURCE, NULL_TYPE, windowStart.plusSeconds(2), Map.of("bid", 3.0)));

            List<ProcessedEvent.ProcessedAlert> nullAlerts = alertsOf(driver.output(), AlertType.NULL_SPIKE);
            assertThat(nullAlerts).hasSize(1);
            assertThat(nullAlerts.get(0).evidence().get("null_count").asLong()).isEqualTo(2);
            assertThat(nullAlerts.get(0).evidence().get("total_count").asLong()).isEqualTo(3);
        }
    }

    @Test
    void fiveEventBurstOverSingleEventBaselineProducesOneAnomalySpike() {
        try (Driver driver = driver(Map.of())) {
            Instant windowStart = Instant.now().truncatedTo(ChronoUnit.MINUTES);
            driver.pipe(new DataEvent("base-2", SOURCE, ANOMALY_TYPE, windowStart.minusSeconds(120), Map.of("bid", 1.0)));
            driver.pipe(new DataEvent("base-1", SOURCE, ANOMALY_TYPE, windowStart.minusSeconds(60), Map.of("bid", 2.0)));
            for (int i = 0; i < 5; i++) {
                driver.pipe(new DataEvent("burst-" + i, SOURCE, ANOMALY_TYPE,
                        windowStart.plusSeconds(i), Map.of("bid", 10.0 + i)));
            }

            List<KeyValue<String, ProcessedEvent>> results = driver.output();
            assertThat(results).hasSize(7);

            List<ProcessedEvent.ProcessedAlert> anomalyAlerts = alertsOf(results, AlertType.ANOMALY_SPIKE);
            assertThat(anomalyAlerts)
                    .as("count 5 against baseline 1.0 gives ratio 5.0: fire once")
                    .hasSize(1);
            ProcessedEvent.ProcessedAlert alert = anomalyAlerts.get(0);
            assertThat(alert.evidence().get("baseline").asDouble()).isEqualTo(1.0);
            assertThat(alert.evidence().get("current_value").asLong()).isEqualTo(5);
            assertThat(alert.evidence().get("ratio").asDouble()).isEqualTo(5.0);
        }
    }

    @Test
    void twoByTwoBaselineThenEightEventBurstStillProducesOneAnomalySpike() {
        try (Driver driver = driver(Map.of())) {
            Instant windowStart = Instant.now().truncatedTo(ChronoUnit.MINUTES);
            driver.pipe(new DataEvent("old-a0", SOURCE, ANOMALY_TYPE, windowStart.minusSeconds(120), Map.of("bid", 1.0)));
            driver.pipe(new DataEvent("old-a1", SOURCE, ANOMALY_TYPE, windowStart.minusSeconds(119), Map.of("bid", 2.0)));
            driver.pipe(new DataEvent("old-b0", SOURCE, ANOMALY_TYPE, windowStart.minusSeconds(60), Map.of("bid", 3.0)));
            driver.pipe(new DataEvent("old-b1", SOURCE, ANOMALY_TYPE, windowStart.minusSeconds(59), Map.of("bid", 4.0)));
            for (int i = 0; i < 8; i++) {
                driver.pipe(new DataEvent("old-c" + i, SOURCE, ANOMALY_TYPE,
                        windowStart.plusSeconds(i), Map.of("bid", 100.0 + i)));
            }

            List<ProcessedEvent.ProcessedAlert> anomalyAlerts = alertsOf(driver.output(), AlertType.ANOMALY_SPIKE);
            assertThat(anomalyAlerts).hasSize(1);
            assertThat(anomalyAlerts.get(0).evidence().get("baseline").asDouble()).isEqualTo(2.0);
        }
    }

    @Test
    void outOfOrderPreviousWindowEventDoesNotEraseCurrentWindowCounts() {
        try (Driver driver = driver(Map.of(NULL_TYPE, ASK_NUMBER))) {
            Instant windowStart = Instant.now().truncatedTo(ChronoUnit.MINUTES);
            driver.pipe(new DataEvent("ooo-0", SOURCE, NULL_TYPE, windowStart, Map.of("bid", 1.0, "ask", 2.0)));
            driver.pipe(new DataEvent("ooo-1", SOURCE, NULL_TYPE, windowStart.plusSeconds(1), Map.of("bid", 2.0)));
            driver.pipe(new DataEvent("ooo-2", SOURCE, NULL_TYPE,
                    windowStart.minusSeconds(60), Map.of("bid", 3.0, "ask", 4.0)));
            driver.pipe(new DataEvent("ooo-3", SOURCE, NULL_TYPE, windowStart.plusSeconds(2), Map.of("bid", 4.0)));

            List<ProcessedEvent.ProcessedAlert> nullAlerts = alertsOf(driver.output(), AlertType.NULL_SPIKE);
            assertThat(nullAlerts)
                    .as("the T window reaches 2 missing of 3 total only if the T-1 event did not wipe it")
                    .hasSize(1);
            assertThat(nullAlerts.get(0).evidence().get("null_count").asLong()).isEqualTo(2);
            assertThat(nullAlerts.get(0).evidence().get("total_count").asLong()).isEqualTo(3);
            assertThat(nullAlerts.get(0).evidence().get("window_start").asText()).isEqualTo(windowStart.toString());
        }
    }

    @Test
    void repeatedEventIdKeepsInputsAndFlagsBusinessDuplicate() {
        try (Driver driver = driver(Map.of())) {
            DataEvent event = new DataEvent("dup-1", SOURCE, ANOMALY_TYPE,
                    Instant.now().truncatedTo(ChronoUnit.MINUTES), Map.of("bid", 1.0));
            driver.pipe(event);
            driver.pipe(event);

            List<KeyValue<String, ProcessedEvent>> results = driver.output();
            assertThat(results).hasSize(2);
            assertThat(results.get(1).value.alerts()).anySatisfy(a ->
                    assertThat(a.type()).isEqualTo(AlertType.DUPLICATE_EVENT));
            assertThat(results.get(0).value.alerts()).isEmpty();
        }
    }

    // ---------------------------------------------------------------- window boundaries

    @Test
    void expiredEventKeepsRawEvidenceWithoutChangingTheWindow() {
        try (Driver driver = driver(Map.of(NULL_TYPE, ASK_NUMBER))) {
            Instant windowStart = Instant.now().truncatedTo(ChronoUnit.MINUTES);
            driver.pipe(new DataEvent("exp-0", SOURCE, NULL_TYPE, windowStart, Map.of("bid", 1.0)));
            driver.pipe(new DataEvent("exp-1", SOURCE, NULL_TYPE, windowStart.plusSeconds(1), Map.of("bid", 2.0)));
            // Far behind the watermark: windowEnd + grace is long past.
            driver.pipe(new DataEvent("exp-old", SOURCE, NULL_TYPE,
                    windowStart.minusSeconds(3600), Map.of("bid", 3.0)));

            List<KeyValue<String, ProcessedEvent>> results = driver.output();
            assertThat(results).hasSize(3);
            ProcessedEvent expired = results.get(2).value;
            assertThat(expired.windowEvaluation().outcome()).isEqualTo(WindowEvaluation.Outcome.EXPIRED);
            assertThat(expired.windowEvaluation().detail()).isEqualTo("WINDOW_CLOSED");

            // The current window still has only the two live events; a third missing value fires.
            driver.pipe(new DataEvent("exp-2", SOURCE, NULL_TYPE, windowStart.plusSeconds(2), Map.of("bid", 4.0)));
            List<ProcessedEvent.ProcessedAlert> nullAlerts = alertsOf(driver.output(), AlertType.NULL_SPIKE);
            assertThat(nullAlerts).hasSize(1);
            assertThat(nullAlerts.get(0).evidence().get("total_count").asLong()).isEqualTo(3);
        }
    }

    @Test
    void futureEventIsExcludedAndDoesNotAdvanceTheWatermark() {
        try (Driver driver = driver(Map.of(NULL_TYPE, ASK_NUMBER))) {
            Instant windowStart = Instant.now().truncatedTo(ChronoUnit.MINUTES);
            driver.pipe(new DataEvent("fut-0", SOURCE, NULL_TYPE, windowStart, Map.of("bid", 1.0)));
            // Beyond the future tolerance: must not advance the scope watermark.
            driver.pipe(new DataEvent("fut-far", SOURCE, NULL_TYPE,
                    Instant.now().plus(Duration.ofMinutes(30)), Map.of("bid", 2.0)));
            driver.pipe(new DataEvent("fut-1", SOURCE, NULL_TYPE, windowStart.plusSeconds(1), Map.of("bid", 3.0)));
            driver.pipe(new DataEvent("fut-2", SOURCE, NULL_TYPE, windowStart.plusSeconds(2), Map.of("bid", 4.0)));

            List<KeyValue<String, ProcessedEvent>> results = driver.output();
            assertThat(results).hasSize(4);
            assertThat(results.get(1).value.windowEvaluation().outcome())
                    .isEqualTo(WindowEvaluation.Outcome.FUTURE);

            List<ProcessedEvent.ProcessedAlert> nullAlerts = alertsOf(results, AlertType.NULL_SPIKE);
            assertThat(nullAlerts)
                    .as("if the FUTURE event had advanced the watermark the T window would have expired")
                    .hasSize(1);
            assertThat(nullAlerts.get(0).evidence().get("total_count").asLong()).isEqualTo(3);
        }
    }

    @Test
    void scopeWatermarksAreIsolatedPerSource() {
        try (Driver driver = driver(Map.of(NULL_TYPE, ASK_NUMBER))) {
            Instant windowStart = Instant.now().truncatedTo(ChronoUnit.MINUTES);
            // Source A runs ahead at T.
            for (int i = 0; i < 3; i++) {
                driver.pipe(new DataEvent("a-" + i, "source-a", NULL_TYPE,
                        windowStart.plusSeconds(i), Map.of("bid", i)));
            }
            // Source B is a minute behind: it must still be INCLUDED in its own window.
            for (int i = 0; i < 3; i++) {
                driver.pipe(new DataEvent("b-" + i, "source-b", NULL_TYPE,
                        windowStart.minusSeconds(60).plusSeconds(i), Map.of("bid", i)));
            }

            List<KeyValue<String, ProcessedEvent>> results = driver.output();
            assertThat(results.subList(3, 6)).allSatisfy(r ->
                    assertThat(r.value.windowEvaluation().outcome())
                            .isEqualTo(WindowEvaluation.Outcome.INCLUDED));
            assertThat(alertsOf(results, AlertType.NULL_SPIKE)).hasSize(2);
        }
    }

    @Test
    void nextWindowCanFireAgainAfterARelapse() {
        try (Driver driver = driver(Map.of())) {
            // Anchored in the past so the later burst does not approach the future tolerance.
            Instant windowStart = Instant.now().minus(Duration.ofMinutes(20)).truncatedTo(ChronoUnit.MINUTES);
            driver.pipe(new DataEvent("w0-base", SOURCE, ANOMALY_TYPE, windowStart.minusSeconds(120), Map.of("bid", 1.0)));
            driver.pipe(new DataEvent("w1-base", SOURCE, ANOMALY_TYPE, windowStart.minusSeconds(60), Map.of("bid", 2.0)));
            for (int i = 0; i < 8; i++) {
                driver.pipe(new DataEvent("w0-burst-" + i, SOURCE, ANOMALY_TYPE,
                        windowStart.plusSeconds(i), Map.of("bid", 10.0 + i)));
            }
            // Next window is quiet (one event), then a much larger burst follows.
            driver.pipe(new DataEvent("w1-quiet", SOURCE, ANOMALY_TYPE,
                    windowStart.plusSeconds(60), Map.of("bid", 20.0)));
            for (int i = 0; i < 20; i++) {
                driver.pipe(new DataEvent("w2-burst-" + i, SOURCE, ANOMALY_TYPE,
                    windowStart.plusSeconds(120 + i), Map.of("bid", 30.0 + i)));
            }

            List<ProcessedEvent.ProcessedAlert> anomalyAlerts = alertsOf(driver.output(), AlertType.ANOMALY_SPIKE);
            assertThat(anomalyAlerts)
                    .as("one alert per firing window, never twice inside the same window")
                    .hasSize(2);
            assertThat(anomalyAlerts.get(0).evidence().get("window_start").asText())
                    .isNotEqualTo(anomalyAlerts.get(1).evidence().get("window_start").asText());
        }
    }

    @Test
    void insufficientHistoryRecordsWarmingUpWithoutAlerting() {
        try (Driver driver = driver(Map.of())) {
            Instant windowStart = Instant.now().truncatedTo(ChronoUnit.MINUTES);
            for (int i = 0; i < 6; i++) {
                driver.pipe(new DataEvent("warm-" + i, SOURCE, ANOMALY_TYPE,
                        windowStart.plusSeconds(i), Map.of("bid", 10.0 + i)));
            }

            List<KeyValue<String, ProcessedEvent>> results = driver.output();
            assertThat(alertsOf(results, AlertType.ANOMALY_SPIKE)).isEmpty();
            assertThat(results).allSatisfy(r ->
                    assertThat(r.value.windowEvaluation().detail()).isEqualTo("WARMING_UP"));
        }
    }

    @Test
    void zeroBaselineRecordsBaselineZeroWithoutAlerting() {
        try (Driver driver = driver(Map.of())) {
            Instant windowStart = Instant.now().truncatedTo(ChronoUnit.MINUTES);
            // Observation starts four windows earlier, so the two windows before the burst are
            // inside the observed range and are zero.
            driver.pipe(new DataEvent("zero-start", SOURCE, ANOMALY_TYPE,
                    windowStart.minusSeconds(240), Map.of("bid", 1.0)));
            driver.pipe(new DataEvent("zero-2", SOURCE, ANOMALY_TYPE, windowStart.plusSeconds(0), Map.of("bid", 3.0)));
            for (int i = 1; i < 6; i++) {
                driver.pipe(new DataEvent("zero-b" + i, SOURCE, ANOMALY_TYPE,
                        windowStart.plusSeconds(i), Map.of("bid", 10.0 + i)));
            }

            List<KeyValue<String, ProcessedEvent>> results = driver.output();
            assertThat(alertsOf(results, AlertType.ANOMALY_SPIKE))
                    .as("a zero baseline must not be divided into an infinite ratio")
                    .isEmpty();
            assertThat(results).anySatisfy(r ->
                    assertThat(r.value.windowEvaluation().detail()).isEqualTo("BASELINE_ZERO"));
        }
    }

    @Test
    void baselineFromTheGlobalStoreMarksEventsAppliedAndRunsTheChecks() {
        try (Driver driver = driver(Map.of(NULL_TYPE, ASK_NUMBER))) {
            Instant windowStart = Instant.now().truncatedTo(ChronoUnit.MINUTES);
            for (int i = 0; i < 3; i++) {
                driver.pipe(new DataEvent("applied-" + i, SOURCE, NULL_TYPE,
                        windowStart.plusSeconds(i), Map.of("bid", i)));
            }

            List<KeyValue<String, ProcessedEvent>> results = driver.output();
            assertThat(results).allSatisfy(r ->
                    assertThat(r.value.baselineStatus()).isEqualTo("APPLIED"));
            assertThat(alertsOf(results, AlertType.NULL_SPIKE))
                    .as("with a baseline in the global store the null checks actually run")
                    .hasSize(1);
        }
    }

    @Test
    void missingBaselineMarksBaselinePendingInsteadOfPassing() {
        try (Driver driver = driver(Map.of())) {
            Instant windowStart = Instant.now().truncatedTo(ChronoUnit.MINUTES);
            driver.pipe(new DataEvent("pending-0", SOURCE, NULL_TYPE, windowStart, Map.of("bid", 1.0)));

            ProcessedEvent event = driver.output().get(0).value;
            assertThat(event.baselineStatus()).isEqualTo("PENDING");
            assertThat(event.windowEvaluation().outcome()).isEqualTo(WindowEvaluation.Outcome.INCLUDED);
        }
    }

    // ---------------------------------------------------------------- delivery identity

    @Test
    void redeliveredEnvelopeIsMarkedAndDoesNotAddDetectionCounts() {
        try (Driver driver = driver(Map.of(NULL_TYPE, ASK_NUMBER))) {
            Instant windowStart = Instant.now().truncatedTo(ChronoUnit.MINUTES);
            RawEnvelope envelope = new RawEnvelope(RawEnvelope.CONTRACT_VERSION, UUID.randomUUID(),
                    new DataEvent("replay-0", SOURCE, NULL_TYPE, windowStart, Map.of("bid", 1.0)),
                    Instant.now(), RawEnvelope.Origin.REST, RawEnvelope.Mode.LIVE, null, null);
            driver.pipeEnvelope(envelope);
            driver.pipeEnvelope(envelope);
            driver.pipeEnvelope(envelope);

            List<KeyValue<String, ProcessedEvent>> results = driver.output();
            assertThat(results).hasSize(3);
            assertThat(results.subList(1, 3)).allSatisfy(r -> {
                assertThat(r.value.windowEvaluation().outcome())
                        .isEqualTo(WindowEvaluation.Outcome.REDELIVERY);
                assertThat(r.value.alerts()).isEmpty();
            });
            // One delivery only: with a baseline field missing three times the window would fire
            // on the third record if redeliveries were counted.
            assertThat(alertsOf(results, AlertType.NULL_SPIKE)).isEmpty();
        }
    }

    @Test
    void reusedIngestionIdWithDifferentContentIsAConflict() {
        try (Driver driver = driver(Map.of(NULL_TYPE, ASK_NUMBER))) {
            Instant windowStart = Instant.now().truncatedTo(ChronoUnit.MINUTES);
            UUID id = UUID.randomUUID();
            driver.pipeEnvelope(new RawEnvelope(RawEnvelope.CONTRACT_VERSION, id,
                    new DataEvent("conflict-0", SOURCE, NULL_TYPE, windowStart, Map.of("bid", 1.0, "ask", 2.0)),
                    Instant.now(), RawEnvelope.Origin.REST, RawEnvelope.Mode.LIVE, null, null));
            driver.pipeEnvelope(new RawEnvelope(RawEnvelope.CONTRACT_VERSION, id,
                    new DataEvent("conflict-0", SOURCE, NULL_TYPE, windowStart, Map.of("bid", 1.0)),
                    Instant.now(), RawEnvelope.Origin.REST, RawEnvelope.Mode.LIVE, null, null));

            List<KeyValue<String, ProcessedEvent>> results = driver.output();
            assertThat(results.get(1).value.windowEvaluation().outcome())
                    .isEqualTo(WindowEvaluation.Outcome.CONFLICT);
            assertThat(results.get(1).value.alerts()).isEmpty();
            assertThat(alertsOf(results, AlertType.NULL_SPIKE))
                    .as("a reused identity must never pollute detection counts")
                    .isEmpty();
        }
    }

    @Test
    void sameEventIdAndPayloadInDifferentScopesDoNotCrossAlert() {
        try (Driver driver = driver(Map.of())) {
            Instant ts = Instant.now().truncatedTo(ChronoUnit.MINUTES);
            DataEvent shared = new DataEvent("shared-id", "source-a", ANOMALY_TYPE, ts, Map.of("bid", 1.0));
            DataEvent sameIdOtherSource = new DataEvent("shared-id", "source-b", ANOMALY_TYPE, ts, Map.of("bid", 1.0));
            driver.pipe(shared);
            driver.pipe(sameIdOtherSource);

            List<KeyValue<String, ProcessedEvent>> results = driver.output();
            assertThat(results).hasSize(2);
            assertThat(results).allSatisfy(r -> assertThat(r.value.alerts()).isEmpty());
        }
    }

    @Test
    void bootstrapModeSkipsLiveWindowsButKeepsTheEvent() {
        try (Driver driver = driver(Map.of(NULL_TYPE, ASK_NUMBER))) {
            Instant windowStart = Instant.now().truncatedTo(ChronoUnit.MINUTES);
            for (int i = 0; i < 3; i++) {
                driver.pipeEnvelope(new RawEnvelope(RawEnvelope.CONTRACT_VERSION, UUID.randomUUID(),
                        new DataEvent("boot-" + i, SOURCE, NULL_TYPE, windowStart.plusSeconds(i), Map.of("bid", i)),
                        Instant.now(), RawEnvelope.Origin.GITHUB, RawEnvelope.Mode.BOOTSTRAP, "gh-" + i, null));
            }

            List<KeyValue<String, ProcessedEvent>> results = driver.output();
            assertThat(results).hasSize(3);
            assertThat(results).allSatisfy(r ->
                    assertThat(r.value.windowEvaluation().outcome())
                            .isEqualTo(WindowEvaluation.Outcome.SKIPPED_MODE));
            assertThat(alertsOf(results, AlertType.NULL_SPIKE)).isEmpty();
        }
    }

    @Test
    void qualityStatusFollowsTheDocumentedPrecedence() {
        try (Driver driver = driver(Map.of())) {
            Instant ts = Instant.now().truncatedTo(ChronoUnit.MINUTES);
            DataEvent clean = new DataEvent("status-ok", SOURCE, ANOMALY_TYPE, ts, Map.of("bid", 1.0));
            driver.pipe(clean);
            assertThat(driver.output().get(0).value.qualityStatus()).isEqualTo("OK");

            // Late arrival: flagged LATE.
            driver.pipe(new DataEvent("status-late", SOURCE, ANOMALY_TYPE,
                    ts.minusSeconds(600), Map.of("bid", 2.0)));
            assertThat(driver.output().get(1).value.qualityStatus()).isEqualTo("LATE");

            // Repeated event id: DUPLICATE outranks LATE when both apply.
            driver.pipe(new DataEvent("status-late", SOURCE, ANOMALY_TYPE,
                    ts.minusSeconds(600), Map.of("bid", 2.0)));
            ProcessedEvent both = driver.output().get(2).value;
            assertThat(both.alerts()).extracting(ProcessedEvent.ProcessedAlert::type)
                    .contains(AlertType.DUPLICATE_EVENT, AlertType.LATE_EVENT);
            assertThat(both.qualityStatus()).isEqualTo("DUPLICATE");
        }
    }

    @Test
    void excludedEventsCarryCoverageEvidenceInsteadOfLookingSuccessful() {
        try (Driver driver = driver(Map.of())) {
            Instant windowStart = Instant.now().truncatedTo(ChronoUnit.MINUTES);
            driver.pipe(new DataEvent("cover-0", SOURCE, ANOMALY_TYPE, windowStart, Map.of("bid", 1.0)));
            driver.pipe(new DataEvent("cover-old", SOURCE, ANOMALY_TYPE,
                    windowStart.minusSeconds(3600), Map.of("bid", 2.0)));

            List<KeyValue<String, ProcessedEvent>> results = driver.output();
            assertThat(results.get(0).value.windowEvaluation().outcome())
                    .isEqualTo(WindowEvaluation.Outcome.INCLUDED);
            ProcessedEvent excluded = results.get(1).value;
            assertThat(excluded.windowEvaluation().outcome())
                    .isEqualTo(WindowEvaluation.Outcome.EXPIRED);
            assertThat(excluded.windowEvaluation().scope()).contains(SOURCE).contains(ANOMALY_TYPE);
            // Exclusion is a window decision, not a quality verdict: the event is also late.
            assertThat(excluded.qualityStatus()).isEqualTo("LATE");
            assertThat(excluded.alerts()).extracting(ProcessedEvent.ProcessedAlert::type)
                    .containsExactly(AlertType.LATE_EVENT);
        }
    }

    @Test
    void scopeKeyEncodingCannotCollideThroughSeparators() {
        assertThat(ScopeKey.of("a|b", "c")).isNotEqualTo(ScopeKey.of("a", "b|c"));
        assertThat(ScopeKey.of("a", "b")).isEqualTo("[\"a\",\"b\"]");
    }

    // ---------------------------------------------------------------- harness

    private static List<ProcessedEvent.ProcessedAlert> alertsOf(
            List<KeyValue<String, ProcessedEvent>> results, AlertType type) {
        return results.stream()
                .flatMap(r -> r.value.alerts().stream())
                .filter(a -> a.type() == type)
                .toList();
    }

    /**
     * Builds the pipeline with the given active baselines. Baselines are delivered through the
     * compacted global topic exactly like the relay does, never through a repository.
     */
    private Driver driver(Map<String, Map<String, String>> baselines) {
        TopologySettings settings = new TopologySettings(
                Duration.ofMinutes(5), Duration.ofMinutes(1), 0.6, 3, 2, 2, 3.0, 5);
        QualityStreamsTopology topology = new QualityStreamsTopology(
                serdes, hasher,
                new LateEventDetector(objectMapper, Duration.ofMinutes(5)),
                new FieldRangeDetector(objectMapper, Map.of()),
                new FieldFormatDetector(objectMapper, ""),
                objectMapper, settings);
        return new Driver(topology, baselines);
    }

    /** Wraps a TopologyTestDriver wired to the quality pipeline over an envelope input topic. */
    private class Driver implements AutoCloseable {
        private final TopologyTestDriver driver;
        private final TestInputTopic<String, RawEnvelope> input;
        private final TestOutputTopic<String, ProcessedEvent> output;

        Driver(QualityStreamsTopology topology, Map<String, Map<String, String>> baselines) {
            StreamsBuilder builder = new StreamsBuilder();
            var envelopes = builder.stream("envelope-input",
                    org.apache.kafka.streams.kstream.Consumed.with(
                            org.apache.kafka.common.serialization.Serdes.String(), envelopeSerde()));
            topology.buildPipeline(builder, envelopes);

            Properties props = new Properties();
            props.put(StreamsConfig.APPLICATION_ID_CONFIG, "detection-contract");
            props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "dummy:9092");

            this.driver = new TopologyTestDriver(builder.build(), props);
            this.input = driver.createInputTopic("envelope-input",
                    new StringSerializer(), envelopeSerde().serializer());
            this.output = driver.createOutputTopic(
                    KafkaTopics.QUALITY_EVENTS, new StringDeserializer(), serdes.processedEventSerde().deserializer());
            TestInputTopic<String, BaselineMessage> baselineInput = driver.createInputTopic(
                    KafkaTopics.SCHEMA_BASELINES, new StringSerializer(), serdes.baselineMessageSerde().serializer());
            baselines.forEach((eventType, leaves) ->
                    baselineInput.pipeInput(eventType, new BaselineMessage(eventType, 1L, leaves)));
        }

        void pipe(DataEvent event) {
            pipeEnvelope(RawEnvelope.forRest(event, Instant.now()));
        }

        void pipeEnvelope(RawEnvelope envelope) {
            String key = ScopeKey.of(envelope.event().source(), envelope.event().eventType());
            input.pipeInput(key, envelope, envelope.event().eventTimestamp().toEpochMilli());
        }

        private final List<KeyValue<String, ProcessedEvent>> collected = new java.util.ArrayList<>();

        /** Drains new records and returns everything seen so far in this test. */
        List<KeyValue<String, ProcessedEvent>> output() {
            collected.addAll(output.readKeyValuesToList());
            return List.copyOf(collected);
        }

        @Override
        public void close() {
            driver.close();
        }
    }

    private org.apache.kafka.common.serialization.Serde<RawEnvelope> envelopeSerde() {
        var serializer = new org.springframework.kafka.support.serializer.JsonSerializer<RawEnvelope>(objectMapper).noTypeInfo();
        var deserializer = new org.springframework.kafka.support.serializer.JsonDeserializer<>(RawEnvelope.class, objectMapper);
        deserializer.addTrustedPackages("com.driftwatch.event");
        return org.apache.kafka.common.serialization.Serdes.serdeFrom(serializer, deserializer);
    }
}