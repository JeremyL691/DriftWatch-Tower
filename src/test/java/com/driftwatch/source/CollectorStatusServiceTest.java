package com.driftwatch.source;

import com.driftwatch.config.DriftwatchProperties;
import com.driftwatch.persistence.CollectorStateEntity;
import com.driftwatch.persistence.CollectorStateRepository;
import com.driftwatch.persistence.SourceGapEntity;
import com.driftwatch.persistence.SourceGapRepository;
import com.driftwatch.persistence.SourceOutboxEntity;
import com.driftwatch.persistence.SourceOutboxRepository;
import com.driftwatch.support.MutableClock;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gate G10: collector status reporting (guide 7.2). A successful poll without new events is
 * QUIET; the collector is only LOST once last_poll_success is older than
 * max(15 minutes, 2x the poll interval); backoff and error states are shown separately; the
 * backlog and open gaps are part of the view.
 */
class CollectorStatusServiceTest {

    private static final Instant T0 = Instant.parse("2026-10-01T00:00:00Z");
    private final MutableClock clock = new MutableClock(T0);

    private CollectorStatusService service(CollectorStateEntity state, long pendingOutbox, long openGaps,
                                           Duration pollInterval) {
        CollectorStateRepository states = proxy(CollectorStateRepository.class, (method, args) ->
                method.getName().equals("findAll") ? List.of(state) : throwUnsupported(method.getName()));
        SourceOutboxRepository outbox = proxy(SourceOutboxRepository.class, (method, args) ->
                method.getName().equals("countByStatus") ? pendingOutbox : throwUnsupported(method.getName()));
        SourceGapRepository gaps = proxy(SourceGapRepository.class, (method, args) ->
                method.getName().equals("countByRecoveryState") ? openGaps : throwUnsupported(method.getName()));
        DriftwatchProperties properties = new DriftwatchProperties(
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
        return new CollectorStatusService(states, outbox, gaps, properties, clock);
    }

    private CollectorStateEntity state(String status, Instant lastSuccess, Instant lastEvent) {
        CollectorStateEntity state = new CollectorStateEntity();
        state.setSource("github:apache/kafka");
        state.setStatus(status);
        state.setLastPollSuccess(lastSuccess);
        state.setLastEventAt(lastEvent);
        state.setUpdatedAt(T0);
        return state;
    }

    private static Object throwUnsupported(String name) {
        throw new UnsupportedOperationException(name);
    }

    @Test
    void quietCollectorIsNotLost() {
        CollectorStateEntity state = state(CollectorStateEntity.STATUS_READY,
                T0.minus(Duration.ofMinutes(4)), T0.minus(Duration.ofMinutes(30)));
        Map<String, Object> view = service(state, 0, 0, Duration.ofMinutes(5)).collectors().get(0);
        assertThat(view.get("status")).isEqualTo("QUIET");
        assertThat(view.get("lost")).isEqualTo(false);
        assertThat(view.get("upstream_lag_seconds")).isEqualTo(1800L);
    }

    @Test
    void collectorIsLostOnlyBeyondMaxOfFifteenMinutesAndDoubleTheInterval() {
        CollectorStateEntity state = state(CollectorStateEntity.STATUS_READY,
                T0.minus(Duration.ofMinutes(20)), T0.minus(Duration.ofHours(2)));
        Map<String, Object> view = service(state, 0, 0, Duration.ofMinutes(5)).collectors().get(0);
        assertThat(view.get("status")).isEqualTo("LOST");
        assertThat(view.get("lost")).isEqualTo(true);
        assertThat(view.get("lost_after_seconds")).isEqualTo(Duration.ofMinutes(15).getSeconds());

        CollectorStateEntity longInterval = state(CollectorStateEntity.STATUS_READY,
                T0.minus(Duration.ofMinutes(20)), T0.minus(Duration.ofHours(2)));
        Map<String, Object> longView = service(longInterval, 0, 0, Duration.ofMinutes(30)).collectors().get(0);
        assertThat(longView.get("lost")).as("2x a 30 minute interval is 60 minutes").isEqualTo(false);
        assertThat(longView.get("lost_after_seconds")).isEqualTo(Duration.ofMinutes(60).getSeconds());
    }

    @Test
    void backoffAndErrorAreReportedSeparatelyWithBacklogAndGaps() {
        CollectorStateEntity backoff = state(CollectorStateEntity.STATUS_BACKOFF,
                T0.minus(Duration.ofMinutes(1)), T0.minus(Duration.ofHours(1)));
        Map<String, Object> view = service(backoff, 7, 2, Duration.ofMinutes(5)).collectors().get(0);
        assertThat(view.get("status")).isEqualTo("BACKOFF");
        assertThat(view.get("pending_outbox")).isEqualTo(7L);
        assertThat(view.get("open_gaps")).isEqualTo(2L);

        CollectorStateEntity error = state(CollectorStateEntity.STATUS_ERROR,
                T0.minus(Duration.ofMinutes(1)), null);
        assertThat(service(error, 0, 0, Duration.ofMinutes(5)).collectors().get(0).get("status"))
                .isEqualTo("ERROR");
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
