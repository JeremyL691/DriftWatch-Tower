package com.driftwatch.stream;

import com.driftwatch.quality.ScopeKey;
import org.apache.kafka.streams.state.KeyValueStore;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Versioned policy keys and wall-clock inactivity leases, stored in the existing Long watermark store. */
final class WindowStateNamespace {
    private static final String PREFIX = "wp1:";
    private static final String TOUCH = "touch:";
    private static final String RETENTION = "retention:";
    private WindowStateNamespace() {}

    static String fingerprint(TopologySettings s) {
        String policy = "1/" + s.metricsWindowSize().toMillis() + "/" + s.metricsGrace().toMillis()
                + "/" + s.metricsFutureTolerance().toMillis() + "/" + s.metricsStateRetention().toMillis()
                + "/" + s.anomalyBaselineWindows() + "/" + s.anomalyMinHistoryWindows();
        try {
            return PREFIX + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(policy.getBytes(StandardCharsets.UTF_8))) + "|";
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    static String key(TopologySettings settings, String source, String type, String... components) {
        return settings.windowPolicyNamespace() + ScopeKey.of(source, type, components);
    }
    static String scope(String key) {
        int separator = key.indexOf('|');
        return separator < 0 ? key : key.substring(0, separator + 1) + ScopeKey.scopeOf(key.substring(separator + 1));
    }
    static void touch(KeyValueStore<String, Long> metadata, String scope, long now, long retention) {
        metadata.put(TOUCH + scope, now);
        metadata.put(RETENTION + scope, retention);
    }
    static boolean inactive(KeyValueStore<String, Long> metadata, String scope, long now) {
        if (!scope.startsWith(PREFIX)) return true; // Safe one-time warm-up for legacy keys; never infer their policy.
        Long touched = metadata.get(TOUCH + scope);
        Long retention = metadata.get(RETENTION + scope);
        return touched == null || retention == null || now - touched >= retention;
    }
    static <T> void clearScope(KeyValueStore<String, T> windows, String scope) {
        try (var entries = windows.all()) {
            while (entries.hasNext()) {
                String key = entries.next().key;
                if (scope(key).equals(scope)) windows.delete(key);
            }
        }
    }
    static boolean expired(KeyValueStore<String, Long> metadata, String key, long windowStart, long now) {
        String scope = scope(key);
        if (inactive(metadata, scope, now)) return true;
        Long watermark = metadata.get(scope);
        long retention = metadata.get(RETENTION + scope);
        return watermark != null && windowStart < watermark - retention;
    }
    static void evictMetadata(KeyValueStore<String, Long> metadata, long now) {
        try (var entries = metadata.all()) {
            while (entries.hasNext()) {
                String key = entries.next().key;
                String scope = key.startsWith(TOUCH) ? key.substring(TOUCH.length())
                        : key.startsWith(RETENTION) ? key.substring(RETENTION.length()) : key;
                if (inactive(metadata, scope, now)) {
                    metadata.delete(scope); metadata.delete(TOUCH + scope); metadata.delete(RETENTION + scope);
                }
            }
        }
    }
}
