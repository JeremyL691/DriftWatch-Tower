package com.driftwatch.config;

import com.driftwatch.quality.FieldFormatPatterns;
import com.driftwatch.quality.FieldRangeDetector;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.context.annotation.DependsOn;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/** No database access on the event path. Consumers depend on this bean to load before initialization. */
@Component
@DependsOn("flywayInitializer")
public class RuntimeConfiguration {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final ObjectMapper mapper = JsonMapper.builder()
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES).build();
    private final ManagementConfiguration defaults;
    private final DriftwatchProperties startupProperties;
    private final AtomicReference<Snapshot> effective;

    public record Snapshot(long revision, ManagementConfiguration config,
                           Map<String, FieldRangeDetector.Bounds> ranges, Map<String, Pattern> patterns) {}
    public record State(long saved_revision, Long effective_revision, long live_applied_revision,
                        ManagementConfiguration saved, ManagementConfiguration effective,
                        ManagementConfiguration startup_defaults, List<String> pending_restart_fields) {}
    private record Saved(long revision, ManagementConfiguration config) {}

    public RuntimeConfiguration(DriftwatchProperties properties, JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(manager);
        // Always own the commit boundary: an enclosing transaction cannot roll back an advertised live save.
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.defaults = ManagementConfiguration.from(properties);
        jdbc.update("INSERT INTO management_configuration(id, revision, config) VALUES (1, 0, ?::jsonb) ON CONFLICT (id) DO NOTHING", json(defaults));
        Saved saved = saved();
        // Revision zero preserves the original startup defaults/test profiles. All management writes are bounded.
        if (saved.revision() > 0) saved.config().validate();
        this.startupProperties = saved.config().overlay(properties);
        startupProperties.validateRelations();
        this.effective = new AtomicReference<>(snapshot(saved.revision(), saved.config()));
    }

    public DriftwatchProperties startupProperties() { return startupProperties; }
    public Snapshot snapshot() { return effective.get(); }

    public synchronized State state() {
        Saved saved = saved();
        Snapshot applied = effective.get();
        List<String> pending = changed(applied.config(), saved.config());
        return new State(saved.revision(), pending.isEmpty() ? saved.revision() : null, applied.revision(),
                saved.config(), applied.config(), defaults, pending);
    }

    /** Full document, strict scalar types and allowlist: never bind credentials or upstream URLs. */
    public synchronized State save(JsonNode request, String actor) {
        if (request == null || !request.isObject() || request.size() != 2 || !request.has("config")
                || !request.has("expected_revision") || !request.get("expected_revision").isIntegralNumber()
                || !request.get("expected_revision").canConvertToLong() || request.get("expected_revision").longValue() < 0) {
            throw invalid();
        }
        if (!request.get("config").isObject()) throw invalid();
        ManagementConfiguration candidate = decode(request.get("config"));
        // New edits cannot promise partial-history detection. Keep startup/legacy validation compatible.
        if (candidate.anomaly_min_history_windows() != candidate.anomaly_baseline_windows()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "anomaly_min_history_windows must equal anomaly_baseline_windows; full baseline history is required for new saves");
        }
        try { candidate.validate(); } catch (IllegalArgumentException e) { throw invalid(); }
        long expected = request.get("expected_revision").longValue();
        Snapshot next = snapshot(expected + 1, effective.get().config().withLive(candidate));
        transaction.executeWithoutResult(status -> {
            Saved previous = saved();
            if (previous.revision() != expected || expected == Long.MAX_VALUE) throw conflict();
            int rows = jdbc.update("UPDATE management_configuration SET revision = revision + 1, config = ?::jsonb, updated_at = now() WHERE id = 1 AND revision = ?", json(candidate), expected);
            if (rows != 1) throw conflict();
            jdbc.update("INSERT INTO management_configuration_history(revision, actor, changed_fields, result) VALUES (?, ?, ?::jsonb, 'SAVED')",
                    expected + 1, actor.substring(0, Math.min(actor.length(), 128)), json(changed(previous.config(), candidate)));
        });
        // execute returns only after successful commit. Failed persistence never changes the effective snapshot.
        effective.set(next);
        return state();
    }

    public List<Map<String, Object>> history(int page, int size) {
        if (page < 0 || page > 1000000 || size < 1 || size > 100) throw invalid();
        return jdbc.query("SELECT revision, actor, changed_fields::text, result, created_at FROM management_configuration_history ORDER BY revision DESC LIMIT ? OFFSET ?",
                (rs, row) -> Map.of("revision", rs.getLong(1), "actor", rs.getString(2),
                        "changed_fields", tree(rs.getString(3)), "result", rs.getString(4),
                        "created_at", rs.getTimestamp(5).toInstant()), size, (long) page * size);
    }

    private Saved saved() {
        return jdbc.queryForObject("SELECT revision, config::text FROM management_configuration WHERE id = 1",
                (rs, row) -> new Saved(rs.getLong(1), rs.getLong(1) == 0 ? defaults : decode(tree(rs.getString(2)))));
    }
    private Snapshot snapshot(long revision, ManagementConfiguration config) {
        return new Snapshot(revision, config, config.detectorBounds(), FieldFormatPatterns.parse(config.format_patterns()));
    }
    private List<String> changed(ManagementConfiguration before, ManagementConfiguration after) {
        JsonNode a = mapper.valueToTree(before); JsonNode b = mapper.valueToTree(after);
        List<String> changed = new ArrayList<>();
        a.fieldNames().forEachRemaining(field -> { if (!a.get(field).equals(b.get(field))) changed.add(field); });
        return List.copyOf(changed);
    }
    private ManagementConfiguration decode(JsonNode node) {
        try { return mapper.treeToValue(node, ManagementConfiguration.class); }
        catch (Exception e) { throw invalid(); }
    }
    private JsonNode tree(String json) {
        try { return mapper.readTree(json); } catch (Exception e) { throw new IllegalStateException("Invalid persisted management configuration"); }
    }
    private String json(Object value) {
        try { return mapper.writeValueAsString(value); } catch (Exception e) { throw new IllegalStateException("Cannot serialize management configuration"); }
    }
    private static ResponseStatusException invalid() { return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid management configuration; check the documented field types, bounds and relations"); }
    private static ResponseStatusException conflict() { return new ResponseStatusException(HttpStatus.CONFLICT, "Configuration revision changed; reload before saving"); }
}
