package com.driftwatch.quality;

import com.driftwatch.quality.schema.SchemaInferrer;
import com.driftwatch.quality.schema.SchemaRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@Order(30)
public class SchemaDriftDetector implements QualityDetector {

    private final SchemaRegistry registry;
    private final ObjectMapper objectMapper;

    public SchemaDriftDetector(SchemaRegistry registry, ObjectMapper objectMapper) {
        this.registry = registry;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<DraftAlert> detect(DetectionContext ctx) {
        JsonNode payloadNode = objectMapper.valueToTree(ctx.event().payload());
        SchemaRegistry.Observation obs = registry.observe(
                ctx.event().eventType(), payloadNode, ctx.receivedAt());

        if (!obs.drift()) {
            return List.of();
        }

        SchemaInferrer.SchemaDiff diff = obs.diff();
        ObjectNode evidence = objectMapper.createObjectNode();
        evidence.put("baseline_hash", obs.baseline().getSchemaHash());
        evidence.put("observed_hash", obs.observedHash());
        evidence.set("expected_schema", obs.baseline().getSchemaJson());
        evidence.set("observed_schema", objectMapper.valueToTree(obs.observedSchema()));
        evidence.set("missing_fields", toArray(diff.missing()));
        evidence.set("added_fields", toArray(diff.added()));
        ObjectNode typeChanged = objectMapper.createObjectNode();
        for (Map.Entry<String, String[]> e : diff.typeChanged().entrySet()) {
            ObjectNode change = objectMapper.createObjectNode();
            change.put("expected", e.getValue()[0]);
            change.put("observed", e.getValue()[1]);
            typeChanged.set(e.getKey(), change);
        }
        evidence.set("type_changed", typeChanged);

        String firstChangedField = diff.typeChanged().isEmpty()
                ? (diff.missing().isEmpty()
                    ? (diff.added().isEmpty() ? null : diff.added().iterator().next())
                    : diff.missing().iterator().next())
                : diff.typeChanged().keySet().iterator().next();

        return List.of(new DraftAlert(
                AlertType.SCHEMA_DRIFT,
                Severity.WARN,
                ctx.event().source(),
                ctx.event().eventType(),
                firstChangedField,
                "Schema drift in " + ctx.event().eventType()
                        + " (missing=" + diff.missing().size()
                        + ", added=" + diff.added().size()
                        + ", type_changed=" + diff.typeChanged().size() + ")",
                evidence
        ));
    }

    private ArrayNode toArray(java.util.Set<String> values) {
        ArrayNode arr = objectMapper.createArrayNode();
        values.forEach(arr::add);
        return arr;
    }
}
