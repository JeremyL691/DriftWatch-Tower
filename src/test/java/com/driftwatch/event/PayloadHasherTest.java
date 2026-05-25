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
}
