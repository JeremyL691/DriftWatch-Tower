package com.driftwatch.quality.schema;

import com.driftwatch.config.DriftwatchProperties;
import com.driftwatch.stream.QualityStreamsTopology;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StoreQueryParameters;
import org.apache.kafka.streams.state.QueryableStoreTypes;
import org.apache.kafka.streams.state.ReadOnlyKeyValueStore;
import org.apache.kafka.streams.state.ValueAndTimestamp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.kafka.config.StreamsBuilderFactoryBean;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Read-only inspector of the local Streams global baseline store (guide 4.4).
 * Surfaces whether the current instance has loaded and applied the active schema baseline.
 */
@Component
public class StreamsBaselineStatus {

    private static final Logger log = LoggerFactory.getLogger(StreamsBaselineStatus.class);

    private final ObjectProvider<StreamsBuilderFactoryBean> streamsFactoryProvider;
    private final DriftwatchProperties properties;

    public StreamsBaselineStatus(ObjectProvider<StreamsBuilderFactoryBean> streamsFactoryProvider,
                                 DriftwatchProperties properties) {
        this.streamsFactoryProvider = streamsFactoryProvider;
        this.properties = properties;
    }

    public record CheckResult(String status, Long appliedVersionId, String detail) {
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("status", status);
            if (appliedVersionId != null) {
                map.put("applied_version_id", appliedVersionId);
            }
            if (detail != null) {
                map.put("detail", detail);
            }
            return map;
        }
    }

    public CheckResult check(String eventType, Long expectedVersionId) {
        if (!properties.streams().enabled()) {
            return new CheckResult("UNAVAILABLE", null, "Kafka Streams is disabled in configuration");
        }
        StreamsBuilderFactoryBean factoryBean = streamsFactoryProvider.getIfAvailable();
        KafkaStreams streams = factoryBean == null ? null : factoryBean.getKafkaStreams();
        if (streams == null) {
            return new CheckResult("UNCONFIRMED", null, "Kafka Streams instance is not started");
        }
        if (streams.state() != KafkaStreams.State.RUNNING) {
            return new CheckResult("UNCONFIRMED", null, "Kafka Streams state is " + streams.state());
        }
        try {
            ReadOnlyKeyValueStore<String, ValueAndTimestamp<BaselineMessage>> store = streams.store(
                    StoreQueryParameters.fromNameAndType(
                            QualityStreamsTopology.SCHEMA_BASELINE_STORE,
                            QueryableStoreTypes.timestampedKeyValueStore()
                    )
            );
            ValueAndTimestamp<BaselineMessage> stamped = store.get(eventType);
            if (stamped == null || stamped.value() == null) {
                return new CheckResult("UNCONFIRMED", null, "Baseline not yet found in local Streams global store");
            }
            Long versionId = stamped.value().versionId();
            if (expectedVersionId != null && expectedVersionId.equals(versionId)) {
                return new CheckResult("APPLIED_LOCAL", versionId, "Active baseline version matches local Streams store");
            } else {
                return new CheckResult("PENDING_LOCAL", versionId, "Streams store has version " + versionId + ", expected " + expectedVersionId);
            }
        } catch (Exception e) {
            log.debug("Could not query Streams baseline store for {}: {}", eventType, e.getMessage());
            return new CheckResult("UNCONFIRMED", null, "Global store query error: " + e.getClass().getSimpleName());
        }
    }
}
