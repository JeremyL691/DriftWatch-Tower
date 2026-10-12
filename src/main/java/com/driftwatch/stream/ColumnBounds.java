package com.driftwatch.stream;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Fits payload-derived identifiers (field paths, detector keys, metric names) into their
 * VARCHAR columns. Payload keys are client-controlled and unbounded, and a value that does not
 * fit would fail the whole sink transaction and dead-letter an otherwise valid event.
 */
final class ColumnBounds {

    private static final int HASH_CHARS = 16;

    private ColumnBounds() {}

    /** Keeps the value when it fits; otherwise a readable prefix plus a stable hash suffix. */
    static String fit(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        String suffix = "~" + sha256(value).substring(0, HASH_CHARS);
        return value.substring(0, max - suffix.length()) + suffix;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
