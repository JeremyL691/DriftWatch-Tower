package com.driftwatch.quality.schema;

import com.driftwatch.persistence.BaselineOutboxEntity;
import com.driftwatch.persistence.BaselineOutboxRepository;
import com.driftwatch.persistence.SchemaVersionEntity;
import com.driftwatch.persistence.SchemaVersionRepository;
import com.driftwatch.support.ContainerIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An optional field whose first observation is null has no determined type. Its first concrete
 * value refines the ACTIVE baseline (no drift alert) so the field is checked from then on, while
 * determined types are never changed by a later null.
 */
class SchemaRefinementIntegrationTest extends ContainerIntegrationTest {

    @Autowired SchemaObservationService observationService;
    @Autowired BaselineOutboxRepository outboxRepository;
    @Autowired SchemaVersionRepository versions;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private SchemaObservationService.Observation observe(String eventType, Map<String, Object> payload) {
        return observationService.observe(eventType, objectMapper.valueToTree(payload), Instant.now());
    }

    private static Map<String, Object> event(Object org) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("repo", "apache/kafka");
        payload.put("org_login", org);
        return payload;
    }

    @Test
    void firstConcreteValueRefinesANullTypedLeafWithoutDrift() {
        String eventType = "refine_" + System.nanoTime();
        assertThat(observe(eventType, event(null)).baselineCreated()).isTrue();

        SchemaObservationService.Observation refined = observe(eventType, event("apache"));
        assertThat(refined.changedFields()).as("a refinement is not drift").isEmpty();
        SchemaVersionEntity active = versions.findFirstByEventTypeAndStatus(eventType, SchemaVersionEntity.STATUS_ACTIVE)
                .orElseThrow();
        assertThat(observationService.leafTypesOf(active)).containsEntry("org_login", "STRING")
                .containsEntry("repo", "STRING");
        List<SchemaVersionEntity> all = versions.findByEventTypeOrderByFirstSeenAtAsc(eventType);
        assertThat(all).extracting(SchemaVersionEntity::getStatus)
                .containsExactlyInAnyOrder(SchemaVersionEntity.STATUS_ACTIVE, SchemaVersionEntity.STATUS_SUPERSEDED);

        // Streams learns the refined baseline through the outbox.
        assertThat(outboxRepository.findTop50ByStatusOrderByIdAsc(BaselineOutboxEntity.STATUS_PENDING).stream()
                .filter(row -> row.getEventType().equals(eventType)))
                .anySatisfy(row -> assertThat(row.getVersionId()).isEqualTo(active.getId()));

        // A later null neither changes the determined type nor raises drift evidence.
        SchemaObservationService.Observation laterNull = observe(eventType, event(null));
        assertThat(laterNull.changedFields()).isEmpty();
        assertThat(observationService.leafTypesOf(versions.findFirstByEventTypeAndStatus(eventType,
                SchemaVersionEntity.STATUS_ACTIVE).orElseThrow())).containsEntry("org_login", "STRING");

        // A real type change against the refined baseline is still drift.
        SchemaObservationService.Observation changed = observe(eventType, event(42));
        assertThat(changed.changedFields()).containsExactly("org_login");
    }
}
