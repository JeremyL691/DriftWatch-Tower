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
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SourceHealthServiceTest {

    @Mock
    private RawEventRepository rawEventRepository;

    @Mock
    private QualityAlertRepository alertRepository;

    @Mock
    private MetricWindowRepository metricWindowRepository;

    @Mock
    private SourceHealthRepository sourceHealthRepository;

    @Test
    @SuppressWarnings("unchecked")
    void listRefreshesStaleSourcesAndPersistsAlert() {
        Instant now = Instant.now();
        RawEventEntity latest = new RawEventEntity();
        latest.setSource("demo-stale-source");
        latest.setEventType("market_tick");
        latest.setEventTimestamp(now.minus(Duration.ofMinutes(10)));

        when(rawEventRepository.findDistinctSources()).thenReturn(List.of("demo-stale-source"));
        when(rawEventRepository.findFirstBySourceOrderByEventTimestampDescIdDesc("demo-stale-source"))
                .thenReturn(Optional.of(latest));
        when(rawEventRepository.countBySourceAndEventTimestampAfter(eq("demo-stale-source"), any(Instant.class)))
                .thenReturn(1L);
        when(rawEventRepository.countBySourceAndEventTimestampAfterAndQualityStatus(
                eq("demo-stale-source"), any(Instant.class), any(String.class)))
                .thenReturn(0L);
        when(metricWindowRepository.maxMetricValueBySourceAndMetricNamePrefixAndWindowEndAfter(
                eq("demo-stale-source"), eq("NULL_RATE:"), any(Instant.class)))
                .thenReturn(null);
        when(alertRepository.countBySourceAndCreatedAtAfter(eq("demo-stale-source"), any(Instant.class)))
                .thenReturn(0L);
        when(sourceHealthRepository.findById("demo-stale-source")).thenReturn(Optional.empty());
        when(sourceHealthRepository.findAllByOrderByHealthScoreAscSourceAsc()).thenReturn(List.of());

        SourceHealthService service = new SourceHealthService(
                rawEventRepository,
                alertRepository,
                metricWindowRepository,
                sourceHealthRepository,
                new SourceFreshnessPolicy(Duration.ofMinutes(5), Duration.ofMinutes(30), Duration.ofHours(24)),
                new SourceHealthCalculator(),
                new ObjectMapper()
        );

        service.list();

        ArgumentCaptor<SourceHealthEntity> healthCaptor = ArgumentCaptor.forClass(SourceHealthEntity.class);
        verify(sourceHealthRepository).save(healthCaptor.capture());
        assertThat(healthCaptor.getValue().getStatus()).isEqualTo(SourceHealthEntity.STATUS_STALE);

        ArgumentCaptor<List<QualityAlertEntity>> alertsCaptor = ArgumentCaptor.forClass(List.class);
        verify(alertRepository).saveAll(alertsCaptor.capture());
        assertThat(alertsCaptor.getValue())
                .singleElement()
                .satisfies(alert -> assertThat(alert.getSource()).isEqualTo("demo-stale-source"));
    }
}
