package com.driftwatch.stream;

import com.driftwatch.event.DataEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.stereotype.Component;

/**
 * JSON serdes for the Kafka Streams topology, built from the app's {@link ObjectMapper} so
 * records (JavaTime, records) serialize identically to the REST layer. Type info headers are
 * disabled so the {@link QualityEventSink} consumer controls the target type via its listener
 * config instead of trusting producer-supplied {@code __TypeId__}.
 */
@Component
public class StreamSerdes {

    private static final String[] TRUSTED_PACKAGES = {
            "com.driftwatch.event", "com.driftwatch.quality", "com.driftwatch.stream"
    };

    private final ObjectMapper objectMapper;

    public StreamSerdes(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Serde<DataEvent> dataEventSerde() {
        return serde(DataEvent.class);
    }

    public Serde<ProcessedEvent> processedEventSerde() {
        return serde(ProcessedEvent.class);
    }

    private <T> Serde<T> serde(Class<T> type) {
        JsonSerializer<T> serializer = new JsonSerializer<T>(objectMapper).noTypeInfo();
        JsonDeserializer<T> deserializer = new JsonDeserializer<>(type, objectMapper);
        deserializer.addTrustedPackages(TRUSTED_PACKAGES);
        return Serdes.serdeFrom(serializer, deserializer);
    }
}
