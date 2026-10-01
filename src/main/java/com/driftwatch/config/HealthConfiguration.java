package com.driftwatch.config;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.streams.KafkaStreams;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.StreamsBuilderFactoryBean;
import org.springframework.kafka.core.KafkaAdmin;

import java.util.concurrent.TimeUnit;

/**
 * Readiness contributions for Kafka and Kafka Streams.
 *
 * <p>The database check alone is not enough: an instance whose Streams state is not running yet
 * accepts HTTP traffic but does not process events. The {@code readiness} health group includes
 * these indicators so a health check answers "can this instance process events now".
 */
@Configuration
public class HealthConfiguration {

    /** Broker reachability through a short-timeout admin client. */
    @Bean("kafkaHealthIndicator")
    public BrokerHealthIndicator kafkaHealthIndicator(KafkaAdmin kafkaAdmin) {
        return new BrokerHealthIndicator(AdminClient.create(kafkaAdmin.getConfigurationProperties()));
    }

    /** Kafka Streams runtime state; RUNNING and transient REBALANCING count as available. */
    @Bean("streamsHealthIndicator")
    HealthIndicator streamsHealthIndicator(ObjectProvider<StreamsBuilderFactoryBean> streamsFactoryProvider,
                                           DriftwatchProperties properties) {
        return () -> {
            if (!properties.streams().enabled()) {
                return Health.unknown().withDetail("streams", "disabled").build();
            }
            StreamsBuilderFactoryBean factoryBean = streamsFactoryProvider.getIfAvailable();
            KafkaStreams streams = factoryBean == null ? null : factoryBean.getKafkaStreams();
            if (streams == null) {
                return Health.down().withDetail("streams", "not started").build();
            }
            KafkaStreams.State state = streams.state();
            Health.Builder builder = switch (state) {
                case RUNNING, REBALANCING -> Health.up();
                default -> Health.down();
            };
            return builder.withDetail("state", state.name()).build();
        };
    }

    static final class BrokerHealthIndicator implements HealthIndicator, AutoCloseable {

        private final AdminClient adminClient;

        BrokerHealthIndicator(AdminClient adminClient) {
            this.adminClient = adminClient;
        }

        @Override
        public Health health() {
            try {
                adminClient.describeCluster().nodes().get(2, TimeUnit.SECONDS);
                return Health.up().build();
            } catch (Exception e) {
                return Health.down().withDetail("reason", e.getClass().getSimpleName()).build();
            }
        }

        @Override
        public void close() {
            adminClient.close();
        }
    }
}
