package com.driftwatch.ingestion;

import com.driftwatch.config.DriftwatchProperties;
import com.driftwatch.config.KafkaTopics;
import com.driftwatch.event.DataEvent;
import com.driftwatch.event.RawEnvelope;
import com.driftwatch.quality.ScopeKey;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * One-off legacy bridge (execution guide, section 4.6).
 *
 * <p>Starts at the recorded unprocessed offsets of the legacy raw topic, generates a stable
 * identity per topic/partition/offset and republishes those records as {@link RawEnvelope}
 * records on {@code raw-events-v1}. Re-running it is safe: the same offset always produces the
 * same ingestion id, so the delivery-identity guard and the sink receipt absorb a second pass.
 * The bridge is gated behind {@code driftwatch.bridge.enabled} and is disabled once verified; it
 * is not a long-running second detection pipeline.
 */
@Component
@ConditionalOnProperty(name = "driftwatch.bridge.enabled", havingValue = "true")
public class LegacyBridge implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LegacyBridge.class);

    private final DriftwatchProperties properties;
    private final KafkaTemplate<String, RawEnvelope> rawTemplate;
    private final ObjectMapper objectMapper;

    public LegacyBridge(DriftwatchProperties properties,
                        KafkaTemplate<String, RawEnvelope> rawTemplate,
                        ObjectMapper objectMapper) {
        this.properties = properties;
        this.rawTemplate = rawTemplate;
        this.objectMapper = objectMapper;
    }

    /** Stable identity for one legacy record: the same offset always maps to the same envelope. */
    public static UUID identityFor(String topic, int partition, long offset) {
        return UUID.nameUUIDFromBytes(("legacy-kafka:" + topic + ":" + partition + ":" + offset)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Override
    public void run(ApplicationArguments args) {
        Map<Integer, Long> startOffsets = parseOffsets(properties.bridge().offsets());
        if (startOffsets.isEmpty()) {
            log.warn("legacy bridge is enabled but no offsets were configured; nothing to do");
            return;
        }
        String topic = properties.bridge().legacyTopic();
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("legacy_topic", topic);
        report.put("started_at", Instant.now().toString());
        List<Map<String, Object>> partitions = new ArrayList<>();
        long published = 0;

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(consumerProperties())) {
            for (Map.Entry<Integer, Long> entry : startOffsets.entrySet()) {
                TopicPartition partition = new TopicPartition(topic, entry.getKey());
                consumer.assign(List.of(partition));
                consumer.seek(partition, entry.getValue());
                long end = consumer.endOffsets(List.of(partition)).get(partition);
                long count = 0;
                while (count < properties.bridge().maxRecords()) {
                    ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(2));
                    if (records.isEmpty()) {
                        break;
                    }
                    for (ConsumerRecord<String, String> record : records) {
                        DataEvent event = objectMapper.readValue(record.value(), DataEvent.class);
                        RawEnvelope envelope = new RawEnvelope(RawEnvelope.CONTRACT_VERSION,
                                identityFor(topic, record.partition(), record.offset()),
                                event, Instant.now(), RawEnvelope.Origin.LEGACY, RawEnvelope.Mode.LIVE,
                                topic + ":" + record.partition() + ":" + record.offset(), null);
                        rawTemplate.send(KafkaTopics.RAW_EVENTS_V1,
                                        ScopeKey.of(event.source(), event.eventType()), envelope)
                                .get(10, TimeUnit.SECONDS);
                        published++;
                        count++;
                    }
                }
                Map<String, Object> partitionReport = new LinkedHashMap<>();
                partitionReport.put("partition", entry.getKey());
                partitionReport.put("start_offset", entry.getValue());
                partitionReport.put("end_offset", end);
                partitionReport.put("published", count);
                partitions.add(partitionReport);
            }
        } catch (Exception e) {
            report.put("status", "FAILED");
            report.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
            writeReport(report);
            throw new IllegalStateException("legacy bridge failed", e);
        }
        report.put("status", "COMPLETED");
        report.put("published", published);
        report.put("partitions", partitions);
        report.put("finished_at", Instant.now().toString());
        writeReport(report);
        log.info("legacy bridge completed: {} records published from {}", published, topic);
    }

    private Properties consumerProperties() {
        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                System.getProperty("spring.kafka.bootstrap-servers",
                        System.getenv().getOrDefault("SPRING_KAFKA_BOOTSTRAP_SERVERS", "localhost:9092")));
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "driftwatch-legacy-bridge");
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        return properties;
    }

    /** Parses {@code partition:offset,partition:offset}. */
    static Map<Integer, Long> parseOffsets(String spec) {
        Map<Integer, Long> offsets = new LinkedHashMap<>();
        if (spec == null || spec.isBlank()) {
            return offsets;
        }
        for (String entry : spec.split(",")) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String[] parts = trimmed.split(":");
            if (parts.length != 2) {
                throw new IllegalArgumentException("bridge offset '" + trimmed + "' must be partition:offset");
            }
            offsets.put(Integer.parseInt(parts[0].trim()), Long.parseLong(parts[1].trim()));
        }
        return offsets;
    }

    private void writeReport(Map<String, Object> report) {
        String path = properties.bridge().reportPath();
        if (path == null || path.isBlank()) {
            log.info("legacy bridge report: {}", report);
            return;
        }
        try {
            java.nio.file.Path target = java.nio.file.Path.of(path);
            if (target.getParent() != null) {
                java.nio.file.Files.createDirectories(target.getParent());
            }
            java.nio.file.Files.writeString(target, objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(report));
            log.info("legacy bridge report written to {}", path);
        } catch (Exception e) {
            log.warn("could not write the bridge report to {}: {}", path, e.getMessage());
        }
    }
}
