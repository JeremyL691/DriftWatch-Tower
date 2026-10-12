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
    /**
     * Last watermark of a scope whose window state was evicted, and when it was retired. Window
     * counts may be dropped after the inactivity lease, but forgetting how far event time had
     * advanced would let an old event re-seed the scope and be counted into a long-closed window
     * (guide 5.1: windowEnd + grace before the watermark is EXPIRED).
     */
    static final String SEED = "seed:";
    static final String SEED_AT = "seedat:";
    static final long SEED_RETENTION_MS = java.time.Duration.ofHours(24).toMillis();
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
    /** Keeps the scope's watermark as a seed (monotonic) and removes the live watermark. */
    static void retireWatermark(KeyValueStore<String, Long> metadata, String scope, long now) {
        Long watermark = metadata.get(scope);
        if (watermark != null) {
            Long seed = metadata.get(SEED + scope);
            metadata.put(SEED + scope, seed == null ? watermark : Math.max(seed, watermark));
            metadata.put(SEED_AT + scope, now);
        }
        metadata.delete(scope);
    }

    /** The retired watermark of a scope while it is still within {@link #SEED_RETENTION_MS}. */
    static Long seed(KeyValueStore<String, Long> metadata, String scope, long now) {
        Long seededAt = metadata.get(SEED_AT + scope);
        if (seededAt == null || now - seededAt >= SEED_RETENTION_MS) {
            return null;
        }
        return metadata.get(SEED + scope);
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
        java.util.Set<String> scopes = new java.util.HashSet<>();
        java.util.Set<String> seeds = new java.util.HashSet<>();
        try (var entries = metadata.all()) {
            while (entries.hasNext()) {
                String key = entries.next().key;
                if (key.startsWith(SEED_AT)) seeds.add(key.substring(SEED_AT.length()));
                else if (key.startsWith(SEED)) seeds.add(key.substring(SEED.length()));
                else if (key.startsWith(TOUCH)) scopes.add(key.substring(TOUCH.length()));
                else if (key.startsWith(RETENTION)) scopes.add(key.substring(RETENTION.length()));
                else scopes.add(key);
            }
        }
        for (String scope : scopes) {
            if (inactive(metadata, scope, now)) {
                // Only legacy (un-namespaced) keys are dropped outright; a policy scope keeps its
                // watermark as a bounded seed so a late old event is still classified EXPIRED.
                if (scope.startsWith(PREFIX)) {
                    retireWatermark(metadata, scope, now);
                    seeds.remove(scope);
                } else {
                    metadata.delete(scope);
                }
                metadata.delete(TOUCH + scope); metadata.delete(RETENTION + scope);
            }
        }
        for (String scope : seeds) {
            Long seededAt = metadata.get(SEED_AT + scope);
            if (seededAt == null || now - seededAt >= SEED_RETENTION_MS) {
                metadata.delete(SEED + scope); metadata.delete(SEED_AT + scope);
            }
        }
    }
}
