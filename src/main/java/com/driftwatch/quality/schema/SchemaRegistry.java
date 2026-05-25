package com.driftwatch.quality.schema;

import com.driftwatch.persistence.SchemaVersionEntity;
import com.driftwatch.persistence.SchemaVersionRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Manages observed schemas per event_type. Each call to {@link #observe} upserts
 * the matching schema_versions row and returns an {@link Observation} describing
 * whether the event represents drift vs. the current ACTIVE baseline.
 */
@Service
public class SchemaRegistry {

    private static final TypeReference<TreeMap<String, String>> SCHEMA_MAP =
            new TypeReference<>() {};

    private final SchemaVersionRepository repository;
    private final ObjectMapper objectMapper;

    public SchemaRegistry(SchemaVersionRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    public Observation observe(String eventType, JsonNode payload, Instant now) {
        TreeMap<String, String> observedSchema = SchemaInferrer.infer(payload);
        String observedHash = SchemaHasher.hash(observedSchema);

        Optional<SchemaVersionEntity> existing = repository.findByEventTypeAndSchemaHash(eventType, observedHash);
        Optional<SchemaVersionEntity> active   = repository.findFirstByEventTypeAndStatus(eventType, SchemaVersionEntity.STATUS_ACTIVE);

        // 1) Schema we've seen before → bump last_seen_at and return.
        if (existing.isPresent()) {
            SchemaVersionEntity e = existing.get();
            e.setLastSeenAt(now);
            repository.save(e);
            boolean drift = active.isPresent() && !active.get().getSchemaHash().equals(observedHash);
            return new Observation(active.orElse(null), e, observedSchema, observedHash, drift, diffAgainst(active, observedSchema));
        }

        // 2) Brand-new schema.
        SchemaVersionEntity row = new SchemaVersionEntity();
        row.setEventType(eventType);
        row.setSchemaHash(observedHash);
        row.setSchemaJson(objectMapper.valueToTree(observedSchema));
        row.setFirstSeenAt(now);
        row.setLastSeenAt(now);

        if (active.isEmpty()) {
            // First-ever schema for this event type: it becomes the baseline silently.
            row.setStatus(SchemaVersionEntity.STATUS_ACTIVE);
            SchemaVersionEntity saved = repository.save(row);
            return new Observation(saved, saved, observedSchema, observedHash, false, SchemaInferrer.diff(Map.of(), observedSchema));
        }

        // Active baseline already exists → record as DRIFTING and flag drift.
        row.setStatus(SchemaVersionEntity.STATUS_DRIFTING);
        SchemaVersionEntity saved = repository.save(row);
        return new Observation(active.get(), saved, observedSchema, observedHash, true, diffAgainst(active, observedSchema));
    }

    private SchemaInferrer.SchemaDiff diffAgainst(Optional<SchemaVersionEntity> active, Map<String, String> observed) {
        if (active.isEmpty()) {
            return SchemaInferrer.diff(Map.of(), observed);
        }
        Map<String, String> baseline = objectMapper.convertValue(active.get().getSchemaJson(), SCHEMA_MAP);
        return SchemaInferrer.diff(baseline, observed);
    }

    public record Observation(
            SchemaVersionEntity baseline,
            SchemaVersionEntity observedRow,
            Map<String, String> observedSchema,
            String observedHash,
            boolean drift,
            SchemaInferrer.SchemaDiff diff
    ) {}
}
