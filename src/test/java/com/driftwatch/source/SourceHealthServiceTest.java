package com.driftwatch.source;

import com.driftwatch.persistence.MetricWindowRepository;
import com.driftwatch.persistence.QualityAlertEntity;
import com.driftwatch.persistence.QualityAlertRepository;
import com.driftwatch.persistence.RawEventEntity;
import com.driftwatch.persistence.RawEventRepository;
import com.driftwatch.persistence.SourceHealthEntity;
import com.driftwatch.persistence.SourceHealthRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class SourceHealthServiceTest {

    @Test
    void listRefreshesStaleSourcesAndPersistsAlert() {
        Instant now = Instant.now();
        RawEventEntity latest = new RawEventEntity();
        latest.setSource("demo-stale-source");
        latest.setEventType("market_tick");
        latest.setEventTimestamp(now.minus(Duration.ofMinutes(10)));

        AtomicReference<SourceHealthEntity> savedHealth = new AtomicReference<>();
        AtomicReference<List<QualityAlertEntity>> savedAlerts = new AtomicReference<>();

        RawEventRepository rawEventRepository = proxy(RawEventRepository.class, (method, args) -> switch (method.getName()) {
            case "findDistinctSources" -> List.of("demo-stale-source");
            case "findFirstBySourceOrderByEventTimestampDescIdDesc" -> Optional.of(latest);
            case "countBySourceAndEventTimestampAfter" -> 1L;
            case "countBySourceAndEventTimestampAfterAndQualityStatus" -> 0L;
            default -> unsupported(method.getName());
        });

        QualityAlertRepository alertRepository = proxy(QualityAlertRepository.class, (method, args) -> switch (method.getName()) {
            case "countBySourceAndCreatedAtAfter" -> 0L;
            case "saveAll" -> {
                @SuppressWarnings("unchecked")
                List<QualityAlertEntity> alerts = (List<QualityAlertEntity>) args[0];
                savedAlerts.set(alerts);
                yield alerts;
            }
            default -> unsupported(method.getName());
        });

        MetricWindowRepository metricWindowRepository = proxy(MetricWindowRepository.class, (method, args) -> switch (method.getName()) {
            case "maxMetricValueBySourceAndMetricNamePrefixAndWindowEndAfter" -> null;
            default -> unsupported(method.getName());
        });

        SourceHealthRepository sourceHealthRepository = proxy(SourceHealthRepository.class, (method, args) -> switch (method.getName()) {
            case "findById" -> Optional.empty();
            case "save" -> {
                SourceHealthEntity entity = (SourceHealthEntity) args[0];
                savedHealth.set(entity);
                yield entity;
            }
            case "findAllByOrderByHealthScoreAscSourceAsc" -> {
                SourceHealthEntity entity = savedHealth.get();
                yield entity == null ? List.of() : List.of(entity);
            }
            default -> unsupported(method.getName());
        });

        SourceHealthService service = new SourceHealthService(
                rawEventRepository,
                alertRepository,
                metricWindowRepository,
                sourceHealthRepository,
                new SourceFreshnessPolicy(Duration.ofMinutes(5), Duration.ofMinutes(30), Duration.ofHours(24)),
                new SourceHealthCalculator(),
                new ObjectMapper()
        );

        List<SourceHealthEntity> rows = service.list();

        assertThat(savedHealth.get()).isNotNull();
        assertThat(savedHealth.get().getStatus()).isEqualTo(SourceHealthEntity.STATUS_STALE);
        assertThat(rows).singleElement().isSameAs(savedHealth.get());

        assertThat(savedAlerts.get())
                .singleElement()
                .satisfies(alert -> assertThat(alert.getSource()).isEqualTo("demo-stale-source"));
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
