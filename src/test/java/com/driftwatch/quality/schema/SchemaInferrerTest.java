package com.driftwatch.quality.schema;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaInferrerTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void flatPayloadProducesLeafTypes() {
        TreeMap<String, String> s = SchemaInferrer.infer(mapper.valueToTree(
                Map.of("symbol", "BTC/USDT", "bid", 100.5, "live", true)));
        assertThat(s).containsEntry("symbol", "STRING")
                .containsEntry("bid", "NUMBER")
                .containsEntry("live", "BOOLEAN");
    }

    @Test
    void nestedObjectProducesDottedPaths() {
        TreeMap<String, String> s = SchemaInferrer.infer(mapper.valueToTree(
                Map.of("payload", Map.of("inner", 1))));
        assertThat(s).containsEntry("payload", "OBJECT")
                .containsEntry("payload.inner", "NUMBER");
    }

    @Test
    void hashIsStableAcrossInsertionOrder() {
        TreeMap<String, String> a = SchemaInferrer.infer(mapper.valueToTree(
                Map.of("a", 1, "b", "x")));
        TreeMap<String, String> b = SchemaInferrer.infer(mapper.valueToTree(
                Map.of("b", "x", "a", 1)));
        assertThat(SchemaHasher.hash(a)).isEqualTo(SchemaHasher.hash(b));
    }

    @Test
    void diffReportsMissingAddedAndTypeChanged() {
        TreeMap<String, String> expected = SchemaInferrer.infer(mapper.valueToTree(
                Map.of("symbol", "BTC/USDT", "bid", 100.5, "ask", 101.0)));
        TreeMap<String, String> observed = SchemaInferrer.infer(mapper.valueToTree(
                Map.of("symbol", "BTC/USDT", "bid", "100.5", "spread", 1.0)));

        SchemaInferrer.SchemaDiff diff = SchemaInferrer.diff(expected, observed);
        assertThat(diff.missing()).containsExactly("ask");
        assertThat(diff.added()).containsExactly("spread");
        assertThat(diff.typeChanged()).containsKey("bid");
        assertThat(diff.typeChanged().get("bid")).containsExactly("NUMBER", "STRING");
    }

    // Array semantics are the tested inferrer contract; the null-spike detector depends on it,
    // so the exact behaviour is locked here (P2.2).

    @Test
    void emptyArrayIsAnArrayLeaf() {
        TreeMap<String, String> s = SchemaInferrer.infer(mapper.valueToTree(Map.of("tags", java.util.List.of())));
        assertThat(s).containsEntry("tags", "ARRAY").hasSize(1);
    }

    @Test
    void heterogeneousArrayIsStillOneArrayLeaf() {
        TreeMap<String, String> s = SchemaInferrer.infer(mapper.valueToTree(
                Map.of("mixed", java.util.List.of(1, "two", true, Map.of("k", 1)))));
        assertThat(s).containsEntry("mixed", "ARRAY").hasSize(1);
    }

    @Test
    void arrayOfObjectsIsNotIntrospected() {
        TreeMap<String, String> s = SchemaInferrer.infer(mapper.valueToTree(
                Map.of("items", java.util.List.of(Map.of("id", 1)))));
        assertThat(s).containsEntry("items", "ARRAY").hasSize(1);
    }

    @Test
    void nestedObjectInsideArrayDoesNotLeakLeafPaths() {
        TreeMap<String, String> s = SchemaInferrer.infer(mapper.valueToTree(
                Map.of("wrapper", Map.of("items", java.util.List.of(Map.of("id", 1))))));
        assertThat(s).containsEntry("wrapper", "OBJECT")
                .containsEntry("wrapper.items", "ARRAY")
                .doesNotContainKey("wrapper.items.id")
                .hasSize(2);
    }

    @Test
    void explicitNullsAreTypedAsNullAndDeepNestingKeepsDottedPaths() {
        java.util.Map<String, Object> level2 = new java.util.LinkedHashMap<>();
        level2.put("leaf", null);
        java.util.Map<String, Object> level1 = new java.util.LinkedHashMap<>();
        level1.put("level2", level2);
        java.util.Map<String, Object> deep = new java.util.LinkedHashMap<>();
        deep.put("level1", level1);
        deep.put("maybe", null);
        TreeMap<String, String> s = SchemaInferrer.infer(mapper.valueToTree(deep));
        assertThat(s).containsEntry("maybe", "NULL")
                .containsEntry("level1", "OBJECT")
                .containsEntry("level1.level2", "OBJECT")
                .containsEntry("level1.level2.leaf", "NULL");
    }
}
