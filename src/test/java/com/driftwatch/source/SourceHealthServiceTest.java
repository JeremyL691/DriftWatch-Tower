package com.driftwatch.source;

import com.driftwatch.operations.DriftwatchMetrics;
import com.driftwatch.persistence.AlertIncidentEntity;
import com.driftwatch.persistence.BaselineOutboxRepository;
import com.driftwatch.persistence.CollectorStateEntity;
import com.driftwatch.persistence.CollectorStateRepository;
import com.driftwatch.persistence.DeadLetterRecordRepository;
import com.driftwatch.persistence.MetricWindowRepository;
import com.driftwatch.persistence.QualityAlertEntity;
import com.driftwatch.persistence.QualityAlertRepository;
import com.driftwatch.persistence.RawEventEntity;
import com.driftwatch.persistence.RawEventRepository;
import com.driftwatch.persistence.SourceHealthEntity;
import com.driftwatch.persistence.SourceHealthRepository;
import com.driftwatch.persistence.SourceOutboxRepository;
import com.driftwatch.quality.AlertType;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SourceHealthServiceTest {

    private static final String GITHUB_SOURCE = "github:apache/kafka";

    @Test
    void listRefreshesStaleSourcesAndPersistsAlert() {
        Instant now = Instant.now();
        RawEventEntity latest = latestEvent("demo-stale-source", "market_tick",
                now.minus(Duration.ofMinutes(10)), now.minus(Duration.ofMinutes(10)));
        Harness harness = new Harness(now, latest, null, null);

        // Reads are pure: the refresh happens on the scheduled path.
        assertThat(harness.service.list()).isEmpty();
        harness.service.refreshAllAndPersist(now);
        List<SourceHealthEntity> rows = harness.service.list();

        assertThat(harness.savedHealth.get()).isNotNull();
        assertThat(harness.savedHealth.get().getStatus()).isEqualTo(SourceHealthEntity.STATUS_STALE);
        assertThat(rows).singleElement().isSameAs(harness.savedHealth.get());

        assertThat(harness.savedAlerts)
                .singleElement()
                .satisfies(alert -> assertThat(alert.getSource()).isEqualTo("demo-stale-source"));
    }

    @Test
    void healthyToStaleTransitionPersistsOneAlert() {
        Instant now = Instant.now();
        RawEventEntity latest = latestEvent("orders-api", "order_created",
                now.minus(Duration.ofMinutes(10)), now.minus(Duration.ofMinutes(10)));
        SourceHealthEntity previous = new SourceHealthEntity();
        previous.setSource("orders-api");
        previous.setStatus(SourceHealthEntity.STATUS_HEALTHY);
        Harness harness = new Harness(now, latest, null, previous);

        List<QualityAlertEntity> alerts = harness.service.refreshAllAndPersist(now);

        assertThat(previous.getStatus()).isEqualTo(SourceHealthEntity.STATUS_STALE);
        assertThat(alerts)
                .singleElement()
                .satisfies(alert -> assertThat(alert.getAlertType()).isEqualTo(AlertType.STALE_SOURCE));
        assertThat(harness.savedAlerts).containsExactlyElementsOf(alerts);
    }

    /**
     * Guide 7.2: an ordinary source is judged by when it was last received. A historic payload whose
     * event_timestamp is a day old must neither hide a live source nor make it look stale.
     */
    @Test
    void historicEventTimestampsDoNotMakeAnIngestingSourceStale() {
        Instant now = Instant.now();
        RawEventEntity latest = latestEvent("quake-feed", "earthquake",
                now.minus(Duration.ofHours(24)), now.minus(Duration.ofMinutes(1)));
        Harness harness = new Harness(now, latest, null, null);

        assertThat(harness.service.refreshAllAndPersist(now)).isEmpty();

        SourceHealthEntity row = harness.savedHealth.get();
        assertThat(row.getStatus()).isNotEqualTo(SourceHealthEntity.STATUS_STALE);
        assertThat(row.getLastSeenAt()).isEqualTo(now.minus(Duration.ofMinutes(1)));
        assertThat(harness.savedAlerts).isEmpty();
    }

    /** A stale transition is an alert, and like sink alerts it joins an incident for its scope. */
    @Test
    void staleTransitionIsCorrelatedIntoAnIncidentAndNamesTheReceiptTime() {
        Instant now = Instant.now();
        RawEventEntity latest = latestEvent("orders-api", "order_created",
                now.minus(Duration.ofHours(2)), now.minus(Duration.ofMinutes(10)));
        Harness harness = new Harness(now, latest, null, null);

        List<QualityAlertEntity> alerts = harness.service.refreshAllAndPersist(now);

        assertThat(alerts).singleElement().satisfies(alert -> {
            assertThat(alert.getMessage()).contains("last received at");
            assertThat(alert.getEvidenceJson().path("freshness_basis").asText()).isEqualTo("last_received_at");
        });
        assertThat(harness.correlatedAlerts).singleElement().isSameAs(alerts.get(0));
    }

    /**
     * A source with a GitHub collector row is judged by the collector's lost rule, which is shared
     * with the collector status view, not by how recently events were received.
     */
    @Test
    void collectorBackedSourceFollowsTheCollectorLostRule() {
        Instant now = Instant.now();
        RawEventEntity latest = latestEvent(GITHUB_SOURCE, "github.PushEvent",
                now.minus(Duration.ofHours(3)), now.minus(Duration.ofHours(3)));

        CollectorStateEntity polling = collector(now.minus(Duration.ofMinutes(2)));
        Harness healthy = new Harness(now, latest, polling, null);
        assertThat(healthy.service.refreshAllAndPersist(now))
                .as("a collector that polled successfully 2 minutes ago is not stale, even with no recent events")
                .isEmpty();
        assertThat(healthy.savedHealth.get().getStatus()).isNotEqualTo(SourceHealthEntity.STATUS_STALE);

        CollectorStateEntity silent = collector(now.minus(Duration.ofMinutes(30)));
        Harness lost = new Harness(now, latest, silent, null);
        List<QualityAlertEntity> alerts = lost.service.refreshAllAndPersist(now);
        assertThat(alerts).singleElement().satisfies(alert -> {
            assertThat(alert.getAlertType()).isEqualTo(AlertType.STALE_SOURCE);
            assertThat(alert.getMessage()).contains("collector has not polled successfully");
            assertThat(alert.getEvidenceJson().path("freshness_basis").asText())
                    .isEqualTo("collector_last_poll_success");
        });
        assertThat(lost.correlatedAlerts).singleElement().isSameAs(alerts.get(0));
    }

    private static CollectorStateEntity collector(Instant lastPollSuccess) {
        CollectorStateEntity state = new CollectorStateEntity();
        state.setSource(GITHUB_SOURCE);
        state.setStatus(CollectorStateEntity.STATUS_READY);
        state.setLastPollSuccess(lastPollSuccess);
        state.setUpdatedAt(lastPollSuccess);
        return state;
    }

    private static RawEventEntity latestEvent(String source, String eventType, Instant eventTimestamp,
                                              Instant receivedAt) {
        RawEventEntity event = new RawEventEntity();
        event.setSource(source);
        event.setEventType(eventType);
        event.setEventTimestamp(eventTimestamp);
        event.setReceivedAt(receivedAt);
        return event;
    }

    /** Wires the real service to in-memory repository stand-ins for one refresh. */
    private static final class Harness {

        final AtomicReference<SourceHealthEntity> savedHealth = new AtomicReference<>();
        final List<QualityAlertEntity> savedAlerts = new ArrayList<>();
        final List<QualityAlertEntity> correlatedAlerts = new ArrayList<>();
        final SourceHealthService service;

        Harness(Instant now, RawEventEntity latest, CollectorStateEntity collector, SourceHealthEntity previous) {
            String source = latest.getSource();
            Clock clock = Clock.fixed(now, ZoneOffset.UTC);

            RawEventRepository rawEvents = proxy(RawEventRepository.class, (method, args) -> switch (method.getName()) {
                case "findDistinctSources" -> List.of(source);
                case "findFirstBySourceOrderByReceivedAtDescIdDesc" -> Optional.of(latest);
                case "countBySourceAndReceivedAtAfter" -> 1L;
                case "countBySourceAndReceivedAtAfterAndQualityStatus" -> 0L;
                default -> unsupported(method.getName());
            });
            QualityAlertRepository alerts = proxy(QualityAlertRepository.class, (method, args) -> switch (method.getName()) {
                case "countBySourceAndCreatedAtAfter" -> 0L;
                case "saveAll" -> {
                    @SuppressWarnings("unchecked")
                    List<QualityAlertEntity> batch = (List<QualityAlertEntity>) args[0];
                    savedAlerts.addAll(batch);
                    yield batch;
                }
                default -> unsupported(method.getName());
            });
            MetricWindowRepository metricWindows = proxy(MetricWindowRepository.class, (method, args) -> switch (method.getName()) {
                case "maxMetricValueBySourceAndMetricNamePrefixAndWindowEndAfter" -> null;
                default -> unsupported(method.getName());
            });
            SourceHealthRepository health = proxy(SourceHealthRepository.class, (method, args) -> switch (method.getName()) {
                case "findById" -> Optional.ofNullable(previous);
                case "save" -> {
                    savedHealth.set((SourceHealthEntity) args[0]);
                    yield args[0];
                }
                case "findAllByOrderByHealthScoreAscSourceAsc" -> {
                    SourceHealthEntity row = savedHealth.get();
                    yield row == null ? List.of() : List.of(row);
                }
                default -> unsupported(method.getName());
            });
            CollectorStateRepository collectors = proxy(CollectorStateRepository.class, (method, args) -> switch (method.getName()) {
                case "findById" -> Optional.ofNullable(collector);
                default -> unsupported(method.getName());
            });
            AlertIncidentService incidents = new AlertIncidentService(null, null, clock) {
                @Override
                public Optional<AlertIncidentEntity> correlate(QualityAlertEntity alert) {
                    correlatedAlerts.add(alert);
                    return Optional.empty();
                }
            };
            DriftwatchMetrics metrics = new DriftwatchMetrics(new SimpleMeterRegistry(),
                    mock(SourceOutboxRepository.class), mock(DeadLetterRecordRepository.class),
                    mock(BaselineOutboxRepository.class), clock);

            this.service = new SourceHealthService(rawEvents, alerts, metricWindows, health,
                    new SourceFreshnessPolicy(Duration.ofMinutes(5), Duration.ofMinutes(30), Duration.ofHours(24)),
                    new SourceHealthCalculator(), new ObjectMapper(),
                    collectors, CollectorStatusFixtures.collectorStatus(clock, Duration.ofMinutes(5)),
                    incidents, metrics);
        }
    }

    private static Object unsupported(String methodName) {
        throw new UnsupportedOperationException("Unexpected repository method call: " + methodName);
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Invocation invocation) {
        return (T) Proxy.newProxyInstance(
                type.getClassLoader(),
                new Class<?>[]{type},
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "toString" -> type.getSimpleName() + "Proxy";
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "equals" -> proxy == args[0];
                            default -> method.invoke(proxy, args);
                        };
                    }
                    return invocation.invoke(method, args == null ? new Object[0] : args);
                }
        );
    }

    @FunctionalInterface
    private interface Invocation {
        Object invoke(java.lang.reflect.Method method, Object[] args) throws Throwable;
    }
}
