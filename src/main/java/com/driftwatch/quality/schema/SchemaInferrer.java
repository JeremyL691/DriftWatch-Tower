package com.driftwatch.quality.schema;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;
import java.util.TreeMap;

/**
 * Walks a JSON payload and returns a sorted flat map of {dotted.path → type-token},
 * where type-token is one of STRING/NUMBER/BOOLEAN/NULL/ARRAY/OBJECT.
 *
 * Nested objects contribute both the parent path (=OBJECT) and the leaf paths.
 * Arrays are recorded as ARRAY (element schema not introspected — MVP).
 */
public final class SchemaInferrer {

    private SchemaInferrer() {}

    public static TreeMap<String, String> infer(JsonNode root) {
        TreeMap<String, String> out = new TreeMap<>();
        if (root == null || root.isNull() || root.isMissingNode()) {
            return out;
        }
        walk("", root, out);
        return out;
    }

    private static void walk(String prefix, JsonNode node, TreeMap<String, String> out) {
        if (node.isObject()) {
            if (!prefix.isEmpty()) out.put(prefix, "OBJECT");
            node.fields().forEachRemaining(e -> {
                String path = prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey();
                walk(path, e.getValue(), out);
            });
            return;
        }
        out.put(prefix.isEmpty() ? "<root>" : prefix, typeOf(node));
    }

    private static String typeOf(JsonNode n) {
        if (n.isTextual())  return "STRING";
        if (n.isNumber())   return "NUMBER";
        if (n.isBoolean())  return "BOOLEAN";
        if (n.isNull())     return "NULL";
        if (n.isArray())    return "ARRAY";
        return "UNKNOWN";
    }

    /** Difference between an expected and an observed schema. */
    public record SchemaDiff(
            java.util.Set<String> missing,
            java.util.Set<String> added,
            Map<String, String[]> typeChanged
    ) {
        public boolean isEmpty() {
            return missing.isEmpty() && added.isEmpty() && typeChanged.isEmpty();
        }
    }

    public static SchemaDiff diff(Map<String, String> expected, Map<String, String> observed) {
        java.util.TreeSet<String> missing = new java.util.TreeSet<>(expected.keySet());
        missing.removeAll(observed.keySet());
        java.util.TreeSet<String> added = new java.util.TreeSet<>(observed.keySet());
        added.removeAll(expected.keySet());
        java.util.TreeMap<String, String[]> changed = new java.util.TreeMap<>();
        for (String key : expected.keySet()) {
            if (observed.containsKey(key) && !expected.get(key).equals(observed.get(key))) {
                changed.put(key, new String[]{expected.get(key), observed.get(key)});
            }
        }
        return new SchemaDiff(missing, added, changed);
    }
}
