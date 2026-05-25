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
}
