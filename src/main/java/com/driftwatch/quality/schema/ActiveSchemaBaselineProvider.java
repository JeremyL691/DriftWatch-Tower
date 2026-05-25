package com.driftwatch.quality.schema;

import com.driftwatch.persistence.SchemaVersionEntity;
import com.driftwatch.persistence.SchemaVersionRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.TreeMap;

@Service
public class ActiveSchemaBaselineProvider implements SchemaBaselineProvider {

    private static final TypeReference<TreeMap<String, String>> SCHEMA_MAP =
            new TypeReference<>() {};

    private final SchemaVersionRepository repository;
    private final ObjectMapper objectMapper;

    public ActiveSchemaBaselineProvider(SchemaVersionRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Override
    public Map<String, String> activeLeafFieldTypes(String eventType) {
        return repository.findFirstByEventTypeAndStatus(eventType, SchemaVersionEntity.STATUS_ACTIVE)
                .map(row -> filterLeafTypes(objectMapper.convertValue(row.getSchemaJson(), SCHEMA_MAP)))
                .orElseGet(this::emptySchema);
    }

    private Map<String, String> filterLeafTypes(Map<String, String> schema) {
        TreeMap<String, String> leafFields = new TreeMap<>();
        schema.forEach((path, type) -> {
            if (!"OBJECT".equals(type)) {
                leafFields.put(path, type);
            }
        });
        return leafFields;
    }

    private Map<String, String> emptySchema() {
        return new TreeMap<>();
    }
}
