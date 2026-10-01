package com.driftwatch.support;

import com.driftwatch.persistence.MetricWindowRepository;
import com.driftwatch.persistence.QualityAlertRepository;
import com.driftwatch.persistence.RawEventRepository;
import com.driftwatch.persistence.SchemaVersionRepository;
import com.driftwatch.persistence.SourceHealthRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;

import java.util.stream.Stream;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
public abstract class ContainerIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16.15"));

    static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("apache/kafka:3.9.2"));

    private static final boolean DOCKER_AVAILABLE = DockerClientFactory.instance().isDockerAvailable();

    static {
        if (DOCKER_AVAILABLE) {
            Startables.deepStart(Stream.of(POSTGRES, KAFKA)).join();
        }
    }

    @Autowired protected RawEventRepository rawEventRepository;
    @Autowired protected QualityAlertRepository qualityAlertRepository;
    @Autowired protected SchemaVersionRepository schemaVersionRepository;
    @Autowired protected MetricWindowRepository metricWindowRepository;
    @Autowired protected SourceHealthRepository sourceHealthRepository;
    @Autowired protected MockMvc mockMvc;

    @Value("${driftwatch.security.admin.username}")
    protected String adminUsername;

    @Value("${driftwatch.security.admin.password}")
    protected String adminPassword;

    @Value("${driftwatch.security.ingest-tokens[0]}")
    protected String ingestToken;

    /** Admin HTTP Basic credentials for management endpoints. */
    protected RequestPostProcessor asAdmin() {
        return httpBasic(adminUsername, adminPassword);
    }

    /** Independent bearer ingest token; can only post events. */
    protected RequestPostProcessor asIngest() {
        return request -> {
            request.addHeader("Authorization", "Bearer " + ingestToken);
            return request;
        };
    }

    /**
     * Acceptance runs set {@code dwt.requireDocker=true} (or {@code DWT_REQUIRE_DOCKER=true}) so a
     * missing Docker daemon fails the gate instead of silently skipping every container test.
     */
    private static final boolean REQUIRE_DOCKER = Boolean.parseBoolean(
            System.getProperty("dwt.requireDocker",
                    System.getenv().getOrDefault("DWT_REQUIRE_DOCKER", "false")));

    @BeforeAll
    static void ensureDockerIsAvailable() {
        if (REQUIRE_DOCKER) {
            org.junit.jupiter.api.Assertions.assertTrue(
                    DOCKER_AVAILABLE,
                    "Docker is required for container-backed integration tests (dwt.requireDocker=true)");
        } else {
            org.junit.jupiter.api.Assumptions.assumeTrue(
                    DOCKER_AVAILABLE,
                    "Docker is required for container-backed integration tests");
        }
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        if (!DOCKER_AVAILABLE) {
            return;
        }
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
