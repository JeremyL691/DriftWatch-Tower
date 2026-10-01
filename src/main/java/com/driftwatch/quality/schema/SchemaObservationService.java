package com.driftwatch.quality.schema;

import com.driftwatch.persistence.BaselineOutboxEntity;
import com.driftwatch.persistence.BaselineOutboxRepository;
import com.driftwatch.persistence.SchemaVersionEntity;
import com.driftwatch.persistence.SchemaVersionRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Schema observation and baseline management, executed inside the sink transaction
 * (execution guide, section 4.4).
 *
 * <p>A PostgreSQL advisory transaction lock serialises observations per event type, and a
 * partial unique index keeps at most one ACTIVE row. The first successful observation becomes
 * ACTIVE and writes a baseline outbox row in the same transaction; later structures are
 * DRIFTING with missing/added/type_changed evidence. NULL values never overwrite a determined
 * non-null type: they are the null-spike detector's evidence, so NULL-versus-value differences
 * are excluded from the type-changed set.
 */
@Service
public class SchemaObservationService {

    private static final TypeReference<TreeMap<String, String>> SCHEMA_MAP = new TypeReference<>() {};

    private final SchemaVersionRepository repository;
    private final BaselineOutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    @PersistenceContext
    private EntityManager entityManager;

    public SchemaObservationService(SchemaVersionRepository repository,
                                    BaselineOutboxRepository outboxRepository,
                                    ObjectMapper objectMapper) {
        this.repository = repository;
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Observation observe(String eventType, JsonNode payload, Instant now) {
        lockEventType(eventType);
        TreeMap<String, String> observedSchema = SchemaInferrer.infer(payload);
        String observedHash = SchemaHasher.hash(observedSchema);

        Optional<SchemaVersionEntity> existing = repository.findByEventTypeAndSchemaHash(eventType, observedHash);
        Optional<SchemaVersionEntity> active =
                repository.findFirstByEventTypeAndStatus(eventType, SchemaVersionEntity.STATUS_ACTIVE);

        if (existing.isPresent()) {
            SchemaVersionEntity row = existing.get();
            row.setLastSeenAt(now);
            repository.save(row);
            boolean drift = active.isPresent() && !active.get().getSchemaHash().equals(observedHash);
            return new Observation(active.orElse(null), row, observedSchema, observedHash, drift,
                    driftDiff(active, observedSchema), false);
        }

        SchemaVersionEntity row = new SchemaVersionEntity();
        row.setEventType(eventType);
        row.setSchemaHash(observedHash);
        row.setSchemaJson(objectMapper.valueToTree(observedSchema));
        row.setFirstSeenAt(now);
        row.setLastSeenAt(now);

        if (active.isEmpty()) {
            row.setStatus(SchemaVersionEntity.STATUS_ACTIVE);
            SchemaVersionEntity saved = repository.save(row);
            appendBaselineOutbox(saved, now);
            return new Observation(saved, saved, observedSchema, observedHash, false,
                    SchemaInferrer.diff(Map.of(), observedSchema), true);
        }

        row.setStatus(SchemaVersionEntity.STATUS_DRIFTING);
        SchemaVersionEntity saved = repository.save(row);
        return new Observation(active.get(), saved, observedSchema, observedHash, true,
                driftDiff(active, observedSchema), false);
    }

    /**
     * Explicitly activates an existing version. The previously active version is demoted to
     * DRIFTING (no version is deleted) and the baseline change is queued in the same transaction.
     */
    @Transactional
    public SchemaVersionEntity activateBaseline(String eventType, Long versionId, Instant now) {
        lockEventType(eventType);
        SchemaVersionEntity target = repository.findById(versionId)
                .orElseThrow(() -> new IllegalArgumentException("schema version " + versionId + " does not exist"));
        if (!target.getEventType().equals(eventType)) {
            throw new IllegalArgumentException(
                    "schema version " + versionId + " belongs to event type " + target.getEventType());
        }
        repository.findFirstByEventTypeAndStatus(eventType, SchemaVersionEntity.STATUS_ACTIVE)
                .filter(current -> !current.getId().equals(target.getId()))
                .ifPresent(current -> {
                    current.setStatus(SchemaVersionEntity.STATUS_DRIFTING);
                    // Flush the demotion first: the partial unique index allows only one ACTIVE
                    // row per event type at any point in the transaction.
                    repository.saveAndFlush(current);
                });
        target.setStatus(SchemaVersionEntity.STATUS_ACTIVE);
        SchemaVersionEntity saved = repository.save(target);
        appendBaselineOutbox(saved, now);
        return saved;
    }

    public BaselineOutboxEntity appendBaselineOutbox(SchemaVersionEntity version, Instant now) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("event_type", version.getEventType());
        payload.put("version_id", version.getId());
        payload.set("leaf_types", objectMapper.valueToTree(leafTypesOf(version)));
        BaselineOutboxEntity outbox = new BaselineOutboxEntity();
        outbox.setEventType(version.getEventType());
        outbox.setVersionId(version.getId());
        outbox.setPayloadJson(payload);
        outbox.setStatus(BaselineOutboxEntity.STATUS_PENDING);
        outbox.setCreatedAt(now);
        return outboxRepository.save(outbox);
    }

    /** Leaf field types of a version, excluding container paths. */
    public Map<String, String> leafTypesOf(SchemaVersionEntity version) {
        Map<String, String> schema = objectMapper.convertValue(version.getSchemaJson(), SCHEMA_MAP);
        Map<String, String> leaves = new LinkedHashMap<>();
        schema.forEach((path, type) -> {
            if (!"OBJECT".equals(type)) {
                leaves.put(path, type);
            }
        });
        return leaves;
    }

    private void lockEventType(String eventType) {
        entityManager.createNativeQuery("SELECT pg_advisory_xact_lock(hashtext(:key)::bigint)")
                .setParameter("key", eventType)
                .getSingleResult();
    }

    private SchemaInferrer.SchemaDiff driftDiff(Optional<SchemaVersionEntity> active,
                                                Map<String, String> observed) {
        if (active.isEmpty()) {
            return SchemaInferrer.diff(Map.of(), observed);
        }
        Map<String, String> baseline = objectMapper.convertValue(active.get().getSchemaJson(), SCHEMA_MAP);
        SchemaInferrer.SchemaDiff raw = SchemaInferrer.diff(baseline, observed);
        Map<String, String[]> typeChanged = new TreeMap<>(raw.typeChanged());
        // NULL never overwrites a determined non-null type; the null detectors own that evidence.
        typeChanged.entrySet().removeIf(entry ->
                "NULL".equals(entry.getValue()[0]) || "NULL".equals(entry.getValue()[1]));
        return new SchemaInferrer.SchemaDiff(raw.missing(), raw.added(), typeChanged);
    }

    public record Observation(
            SchemaVersionEntity baseline,
            SchemaVersionEntity observedRow,
            Map<String, String> observedSchema,
            String observedHash,
            boolean drift,
            SchemaInferrer.SchemaDiff diff,
            boolean baselineCreated
    ) {
        public Set<String> changedFields() {
            if (!drift) {
                return Set.of();
            }
            java.util.Set<String> changed = new java.util.TreeSet<>();
            changed.addAll(diff.missing());
            changed.addAll(diff.added());
            changed.addAll(diff.typeChanged().keySet());
            return changed;
        }
    }
}