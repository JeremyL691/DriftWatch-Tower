package com.driftwatch.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopics {

    /** Versioned topics carrying the RawEnvelope/ProcessedEvent contract. */
    public static final String RAW_EVENTS_V1 = "raw-events-v1";
    public static final String QUALITY_EVENTS_V1 = "quality-events-v1";

    /** Legacy topics: kept for the one-off upgrade bridge (P3.3), not written by the live path. */
    public static final String RAW_EVENTS_LEGACY = "raw-events";
    public static final String QUALITY_EVENTS_LEGACY = "quality-events";
    /** Durable dead-letter topic: the recovery source when the database is unavailable. */
    public static final String DEAD_LETTER_EVENTS = "dead-letter-events-v1";

    /** Compacted topic carrying the active schema baseline per event type. */
    public static final String SCHEMA_BASELINES = "schema-baselines-v1";

    /** Seven days, stated on the topic so the contract does not depend on broker defaults. */
    private static final String SEVEN_DAYS_MS = "604800000";

    @Bean
    NewTopic rawEvents() {
        return TopicBuilder.name(RAW_EVENTS_V1).partitions(3).replicas(1)
                .config("retention.ms", SEVEN_DAYS_MS)
                .build();
    }

    @Bean
    NewTopic qualityEvents() {
        return TopicBuilder.name(QUALITY_EVENTS_V1).partitions(3).replicas(1)
                .config("retention.ms", SEVEN_DAYS_MS)
                .build();
    }

    @Bean
    NewTopic deadLetterEvents() {
        return TopicBuilder.name(DEAD_LETTER_EVENTS).partitions(3).replicas(1)
                .config("retention.ms", SEVEN_DAYS_MS)
                .build();
    }

    @Bean
    NewTopic schemaBaselines() {
        return TopicBuilder.name(SCHEMA_BASELINES).partitions(3).replicas(1)
                .config("cleanup.policy", "compact")
                .build();
    }
}
