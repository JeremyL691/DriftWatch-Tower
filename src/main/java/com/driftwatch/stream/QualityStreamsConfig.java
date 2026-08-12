package com.driftwatch.stream;

import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.kstream.KStream;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafkaStreams;

/**
 * Registers the quality topology as a Kafka Streams application. Gated behind
 * {@code driftwatch.streams.enabled} so the streams application only starts after the legacy
 * consumer path has been retired — running both would double-process every event.
 */
@Configuration
@EnableKafkaStreams
@ConditionalOnProperty(name = "driftwatch.streams.enabled", havingValue = "true")
public class QualityStreamsConfig {

    private final QualityStreamsTopology topology;

    public QualityStreamsConfig(QualityStreamsTopology topology) {
        this.topology = topology;
    }

    @Bean
    KStream<String, ProcessedEvent> qualityPipeline(StreamsBuilder builder) {
        return topology.apply(builder);
    }
}
