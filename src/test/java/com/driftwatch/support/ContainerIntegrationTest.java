package com.driftwatch.support;

import com.driftwatch.persistence.MetricWindowRepository;
import com.driftwatch.persistence.QualityAlertRepository;
import com.driftwatch.persistence.RawEventRepository;
import com.driftwatch.persistence.SchemaVersionRepository;
import com.driftwatch.persistence.SourceHealthRepository;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
public abstract class ContainerIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16"));

    @Container
    static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("apache/kafka-native:3.8.0"));

    @Autowired protected RawEventRepository rawEventRepository;
    @Autowired protected QualityAlertRepository qualityAlertRepository;
    @Autowired protected SchemaVersionRepository schemaVersionRepository;
    @Autowired protected MetricWindowRepository metricWindowRepository;
    @Autowired protected SourceHealthRepository sourceHealthRepository;

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("spring.kafka.consumer.auto-offset-reset", () -> "earliest");
        registry.add("spring.kafka.consumer.group-id", () -> "test-driftwatch");
    }

    @BeforeEach
    void cleanPersistence() {
        qualityAlertRepository.deleteAll();
        sourceHealthRepository.deleteAll();
        metricWindowRepository.deleteAll();
        schemaVersionRepository.deleteAll();
        rawEventRepository.deleteAll();
    }
}
