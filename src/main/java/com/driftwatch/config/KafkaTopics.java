package com.driftwatch.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopics {

    public static final String RAW_EVENTS = "raw-events";
    public static final String QUALITY_EVENTS = "quality-events";
    /** Compacted topic carrying the active schema baseline per event type. */
    public static final String SCHEMA_BASELINES = "schema-baselines-v1";

    @Bean
    NewTopic rawEvents() {
        return TopicBuilder.name(RAW_EVENTS).partitions(3).replicas(1).build();
    }

    @Bean
    NewTopic qualityEvents() {
        return TopicBuilder.name(QUALITY_EVENTS).partitions(3).replicas(1).build();
    }

    @Bean
    NewTopic schemaBaselines() {
        return TopicBuilder.name(SCHEMA_BASELINES).partitions(3).replicas(1)
                .config("cleanup.policy", "compact")
                .build();
    }
}
