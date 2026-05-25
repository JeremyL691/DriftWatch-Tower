package com.driftwatch.quality.schema;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

public final class SchemaHasher {

    private SchemaHasher() {}

    /** SHA-256 over the canonical "{key=type,key=type}" rendering (TreeMap → sorted). */
    public static String hash(Map<String, String> schema) {
        StringBuilder sb = new StringBuilder();
        schema.forEach((k, v) -> sb.append(k).append('=').append(v).append(';'));
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
