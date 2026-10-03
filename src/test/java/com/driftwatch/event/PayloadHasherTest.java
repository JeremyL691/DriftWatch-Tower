package com.driftwatch.event;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PayloadHasherTest {

    private final PayloadHasher hasher = new PayloadHasher();

    @Test
    void sameContentDifferentKeyOrderProducesSameHash() {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("bid", 100.0);
        a.put("ask", 101.0);
        a.put("symbol", "BTC/USDT");

        Map<String, Object> b = new LinkedHashMap<>();
        b.put("symbol", "BTC/USDT");
        b.put("ask", 101.0);
        b.put("bid", 100.0);

        assertThat(hasher.hash(a)).isEqualTo(hasher.hash(b));
    }

    @Test
    void differentContentProducesDifferentHash() {
        assertThat(hasher.hash(Map.of("v", 1)))
                .isNotEqualTo(hasher.hash(Map.of("v", 2)));
    }

    @Test
    void hashIsSha256HexLength() {
        assertThat(hasher.hash(Map.of("k", "v"))).hasSize(64);
    }

    @Test
    void nullPayloadIsHashable() {
        assertThat(hasher.hash(null)).hasSize(64);
    }

    // Canonical-hash contract (P2.2): sorted keys at every level, array order significant,
    // unicode and null values stable.

    @Test
    void nestedKeyOrderDoesNotChangeTheHash() {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("outer", new LinkedHashMap<>(Map.of("b", 2, "a", 1)));
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("outer", new LinkedHashMap<>(Map.of("a", 1, "b", 2)));

        assertThat(hasher.hash(a)).isEqualTo(hasher.hash(b));
    }

    @Test
    void arrayOrderIsSignificant() {
        assertThat(hasher.hash(Map.of("tags", java.util.List.of("a", "b"))))
                .isNotEqualTo(hasher.hash(Map.of("tags", java.util.List.of("b", "a"))));
    }

    @Test
    void unicodeAndNullValuesAreStable() {
        assertThat(hasher.hash(Map.of("name", "数据-α", "note", "naïve")))
                .isEqualTo(hasher.hash(Map.of("note", "naïve", "name", "数据-α")));

        Map<String, Object> withNull = new LinkedHashMap<>();
        withNull.put("a", null);
        withNull.put("b", 1);
        Map<String, Object> sameWithNull = new LinkedHashMap<>();
        sameWithNull.put("b", 1);
        sameWithNull.put("a", null);
        assertThat(hasher.hash(withNull)).isEqualTo(hasher.hash(sameWithNull));
        assertThat(hasher.hash(withNull)).isNotEqualTo(hasher.hash(Map.of("a", "null", "b", 1)));
    }
}
