package com.driftwatch.source;

import com.driftwatch.config.DriftwatchProperties;
import com.driftwatch.operations.DriftwatchMetrics;
import com.driftwatch.persistence.AlertIncidentEntity;
import com.driftwatch.persistence.BaselineOutboxRepository;
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
import com.driftwatch.support.MutableClock;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Gate G10, scheduler half (execution guide, section 7.2): the scheduled refresh, not a dashboard
 * read, produces the STALE transition; the transition fires once per loss; a recovery allows the
 * next loss to alert again; and repeated reads change nothing.
 */
class SourceHealthSchedulerTest {

    private static final String SOURCE = "orders-api";
    private static final String EVENT_TYPE = "order_created";
    private static final Instant T0 = Instant.parse("2026-10-01T00:00:00Z");

    private final MutableClock clock = new MutableClock(T0);
    private final AtomicReference<Instant> latestReceivedAt = new AtomicReference<>(T0.minusSeconds(1));
    private final List<QualityAlertEntity> persistedAlerts = new ArrayList<>();
    private final List<QualityAlertEntity> correlatedAlerts = new ArrayList<>();
    private final AtomicReference<SourceHealthEntity> healthRow = new AtomicReference<>();

    private SourceHealthService service() {
        RawEventRepository rawEvents = proxy(RawEventRepository.class, (method, args) -> switch (method.getName()) {
            case "findDistinctSources" -> List.of(SOURCE);
            case "findFirstBySourceOrderByReceivedAtDescIdDesc" -> {
                RawEventEntity row = new RawEventEntity();
                row.setSource(SOURCE);
                row.setEventType(EVENT_TYPE);
                row.setEventTimestamp(latestReceivedAt.get());
                row.setReceivedAt(latestReceivedAt.get());
                yield Optional.of(row);
            }
            case "countBySourceAndReceivedAtAfter" -> 1L;
            case "countBySourceAndReceivedAtAfterAndQualityStatus" -> 0L;
            default -> throw new UnsupportedOperationException(method.getName());
        });
        QualityAlertRepository alerts = proxy(QualityAlertRepository.class, (method, args) -> switch (method.getName()) {
            case "countBySourceAndCreatedAtAfter" -> 0L;
            case "save" -> {
                QualityAlertEntity entity = (QualityAlertEntity) args[0];
                persistedAlerts.add(entity);
                yield entity;
            }
            case "saveAll" -> {
                @SuppressWarnings("unchecked")
                List<QualityAlertEntity> list = (List<QualityAlertEntity>) args[0];
                persistedAlerts.addAll(list);
                yield list;
            }
            default -> throw new UnsupportedOperationException(method.getName());
        });
        MetricWindowRepository metrics = proxy(MetricWindowRepository.class, (method, args) ->
                method.getName().equals("maxMetricValueBySourceAndMetricNamePrefixAndWindowEndAfter")
                        ? null : throwUnsupported(method.getName()));
        SourceHealthRepository health = proxy(SourceHealthRepository.class, (method, args) -> switch (method.getName()) {
            case "findById" -> Optional.ofNullable(healthRow.get());
            case "save" -> {
                SourceHealthEntity entity = (SourceHealthEntity) args[0];
                healthRow.set(entity);
                yield entity;
            }
            case "findAllByOrderByHealthScoreAscSourceAsc" -> {
                SourceHealthEntity entity = healthRow.get();
                yield entity == null ? List.of() : List.of(entity);
            }
            default -> throw new UnsupportedOperationException(method.getName());
        });
        CollectorStateRepository collectors = proxy(CollectorStateRepository.class, (method, args) -> switch (method.getName()) {
            case "findById" -> Optional.empty();
            case "findAll" -> List.of();
            default -> throwUnsupported(method.getName());
        });
        AlertIncidentService incidents = new AlertIncidentService(null, null, clock) {
            @Override
            public Optional<AlertIncidentEntity> correlate(QualityAlertEntity alert) {
                correlatedAlerts.add(alert);
                return Optional.empty();
            }
        };
        DriftwatchMetrics driftwatchMetrics = new DriftwatchMetrics(new SimpleMeterRegistry(),
                mock(SourceOutboxRepository.class), mock(DeadLetterRecordRepository.class),
                mock(BaselineOutboxRepository.class), clock);
        return new SourceHealthService(rawEvents, alerts, metrics, health,
                new SourceFreshnessPolicy(Duration.ofMinutes(5), Duration.ofMinutes(30), Duration.ofHours(24)),
                new SourceHealthCalculator(), new ObjectMapper(),
                collectors, CollectorStatusFixtures.collectorStatus(clock, Duration.ofMinutes(5)),
                incidents, driftwatchMetrics);
    }

    private static Object throwUnsupported(String name) {
        throw new UnsupportedOperationException(name);
    }

    @Test
    void scheduledRefreshProducesOneTransitionAlertPerLoss() {
        SourceHealthService service = service();

        // Healthy: the last event is recent.
        assertThat(service.refreshScheduled(clock.instant())).isEmpty();

        // Silence beyond the freshness threshold: exactly one alert.
        clock.advance(Duration.ofMinutes(10));
        List<QualityAlertEntity> first = service.refreshScheduled(clock.instant());
        assertThat(first).singleElement()
                .satisfies(alert -> assertThat(alert.getAlertType()).isEqualTo(com.driftwatch.quality.AlertType.STALE_SOURCE));
        assertThat(persistedAlerts).hasSize(1);

        // Still stale: no additional alerts.
        clock.advance(Duration.ofMinutes(10));
        assertThat(service.refreshScheduled(clock.instant())).isEmpty();
        assertThat(persistedAlerts).as("a single loss must alert once").hasSize(1);

        // Recovery: a fresh event, then silence again -> a second transition alert.
        latestReceivedAt.set(clock.instant());
        assertThat(service.refreshScheduled(clock.instant())).isEmpty();
        clock.advance(Duration.ofMinutes(10));
        assertThat(service.refreshScheduled(clock.instant())).hasSize(1);
        assertThat(persistedAlerts).hasSize(2);
        assertThat(correlatedAlerts).as("every transition alert joins an incident").hasSize(2);
    }

    @Test
    void readsHaveNoSideEffects() {
        SourceHealthService service = service();
        // One scheduled refresh while healthy creates the row without alerting.
        service.refreshScheduled(clock.instant());
        assertThat(persistedAlerts).isEmpty();

        // Now the source goes silent; reads must not notice or write anything.
        clock.advance(Duration.ofMinutes(10));
        assertThat(service.list()).hasSize(1);
        assertThat(service.list()).hasSize(1);
        assertThat(service.get(SOURCE)).isPresent();

        assertThat(persistedAlerts).as("GET must not add alerts").isEmpty();
        SourceHealthEntity row = healthRow.get();
        assertThat(row.getStatus()).as("a read must not refresh the status").isEqualTo(SourceHealthEntity.STATUS_HEALTHY);
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Invocation invocation) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
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
                });
    }

    @FunctionalInterface
    private interface Invocation {
        Object invoke(java.lang.reflect.Method method, Object[] args) throws Throwable;
    }
}
