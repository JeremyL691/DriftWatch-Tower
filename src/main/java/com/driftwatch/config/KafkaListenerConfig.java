package com.driftwatch.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Listener error handling for the durable paths (execution guide, section 7.1).
 *
 * <p>Spring Kafka's default handler gives up after a few attempts and then <em>skips</em> the
 * record. That would silently drop an event whose persistence failed and whose dead letter could
 * not be published, and it would drop dead letters while the database is down. Both consumers
 * therefore retry with a fixed backoff indefinitely: the offset only advances once the record was
 * handled, so Kafka remains the durable recovery source.
 */
@Configuration
public class KafkaListenerConfig {

    @Bean
    public ConcurrentKafkaListenerContainerFactory<Object, Object> kafkaListenerContainerFactory(
            ConsumerFactory<Object, Object> consumerFactory) {
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setCommonErrorHandler(new DefaultErrorHandler(new FixedBackOff(2_000L, FixedBackOff.UNLIMITED_ATTEMPTS)));
        return factory;
    }
}
