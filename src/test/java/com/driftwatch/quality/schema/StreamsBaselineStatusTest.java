package com.driftwatch.quality.schema;

import com.driftwatch.config.DriftwatchProperties;
import com.driftwatch.stream.QualityStreamsTopology;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.state.ReadOnlyKeyValueStore;
import org.apache.kafka.streams.state.ValueAndTimestamp;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.kafka.config.StreamsBuilderFactoryBean;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StreamsBaselineStatusTest {

    private DriftwatchProperties properties(boolean streamsEnabled) {
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
                new DriftwatchProperties.Streams(streamsEnabled),
                new DriftwatchProperties.SourceHealth(Duration.ofMinutes(5), Duration.ofMinutes(30),
                        Duration.ofHours(24), Duration.ofMinutes(15)),
                new DriftwatchProperties.Security(
                        new DriftwatchProperties.Security.Admin("u", "p"), List.of(), false),
                new DriftwatchProperties.Source(new DriftwatchProperties.Source.Github(true, "apache/kafka",
                        "https://api.github.com", Duration.ofMinutes(5), 3, 100, Duration.ofSeconds(5),
                        Duration.ofSeconds(20), 300, "", "1.0.0", false, true)),
                new DriftwatchProperties.Bridge(false, "raw-events", "", 10000, ""));
    }

    @Test
    @SuppressWarnings("unchecked")
    void streamsDisabledReturnsUnavailable() {
        ObjectProvider<StreamsBuilderFactoryBean> provider = mock(ObjectProvider.class);
        StreamsBaselineStatus status = new StreamsBaselineStatus(provider, properties(false));
        var result = status.check("demo", 1L);
        assertThat(result.status()).isEqualTo("UNAVAILABLE");
    }

    @Test
    @SuppressWarnings("unchecked")
    void streamsNotStartedReturnsUnconfirmed() {
        ObjectProvider<StreamsBuilderFactoryBean> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        StreamsBaselineStatus status = new StreamsBaselineStatus(provider, properties(true));
        var result = status.check("demo", 1L);
        assertThat(result.status()).isEqualTo("UNCONFIRMED");
        assertThat(result.detail()).contains("not started");
    }

    @Test
    @SuppressWarnings("unchecked")
    void streamsNotRunningReturnsUnconfirmed() {
        ObjectProvider<StreamsBuilderFactoryBean> provider = mock(ObjectProvider.class);
        StreamsBuilderFactoryBean factory = mock(StreamsBuilderFactoryBean.class);
        KafkaStreams streams = mock(KafkaStreams.class);
        when(provider.getIfAvailable()).thenReturn(factory);
        when(factory.getKafkaStreams()).thenReturn(streams);
        when(streams.state()).thenReturn(KafkaStreams.State.REBALANCING);

        StreamsBaselineStatus status = new StreamsBaselineStatus(provider, properties(true));
        var result = status.check("demo", 1L);
        assertThat(result.status()).isEqualTo("UNCONFIRMED");
        assertThat(result.detail()).contains("REBALANCING");
    }

    @Test
    @SuppressWarnings("unchecked")
    void storeMatchingExpectedVersionReturnsAppliedLocal() {
        ObjectProvider<StreamsBuilderFactoryBean> provider = mock(ObjectProvider.class);
        StreamsBuilderFactoryBean factory = mock(StreamsBuilderFactoryBean.class);
        KafkaStreams streams = mock(KafkaStreams.class);
        ReadOnlyKeyValueStore<String, ValueAndTimestamp<BaselineMessage>> store = mock(ReadOnlyKeyValueStore.class);

        when(provider.getIfAvailable()).thenReturn(factory);
        when(factory.getKafkaStreams()).thenReturn(streams);
        when(streams.state()).thenReturn(KafkaStreams.State.RUNNING);
        when(streams.store(any())).thenReturn(store);

        BaselineMessage message = new BaselineMessage("demo", 123L, Map.of("symbol", "STRING"));
        when(store.get("demo")).thenReturn(ValueAndTimestamp.make(message, System.currentTimeMillis()));

        StreamsBaselineStatus status = new StreamsBaselineStatus(provider, properties(true));
        var result = status.check("demo", 123L);
        assertThat(result.status()).isEqualTo("APPLIED_LOCAL");
        assertThat(result.appliedVersionId()).isEqualTo(123L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void storeDifferentVersionReturnsPendingLocal() {
        ObjectProvider<StreamsBuilderFactoryBean> provider = mock(ObjectProvider.class);
        StreamsBuilderFactoryBean factory = mock(StreamsBuilderFactoryBean.class);
        KafkaStreams streams = mock(KafkaStreams.class);
        ReadOnlyKeyValueStore<String, ValueAndTimestamp<BaselineMessage>> store = mock(ReadOnlyKeyValueStore.class);

        when(provider.getIfAvailable()).thenReturn(factory);
        when(factory.getKafkaStreams()).thenReturn(streams);
        when(streams.state()).thenReturn(KafkaStreams.State.RUNNING);
        when(streams.store(any())).thenReturn(store);

        BaselineMessage message = new BaselineMessage("demo", 100L, Map.of("symbol", "STRING"));
        when(store.get("demo")).thenReturn(ValueAndTimestamp.make(message, System.currentTimeMillis()));

        StreamsBaselineStatus status = new StreamsBaselineStatus(provider, properties(true));
        var result = status.check("demo", 123L);
        assertThat(result.status()).isEqualTo("PENDING_LOCAL");
        assertThat(result.appliedVersionId()).isEqualTo(100L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void storeExceptionReturnsUnconfirmed() {
        ObjectProvider<StreamsBuilderFactoryBean> provider = mock(ObjectProvider.class);
        StreamsBuilderFactoryBean factory = mock(StreamsBuilderFactoryBean.class);
        KafkaStreams streams = mock(KafkaStreams.class);

        when(provider.getIfAvailable()).thenReturn(factory);
        when(factory.getKafkaStreams()).thenReturn(streams);
        when(streams.state()).thenReturn(KafkaStreams.State.RUNNING);
        when(streams.store(any())).thenThrow(new IllegalStateException("store error"));

        StreamsBaselineStatus status = new StreamsBaselineStatus(provider, properties(true));
        var result = status.check("demo", 123L);
        assertThat(result.status()).isEqualTo("UNCONFIRMED");
        assertThat(result.detail()).contains("IllegalStateException");
    }
}
