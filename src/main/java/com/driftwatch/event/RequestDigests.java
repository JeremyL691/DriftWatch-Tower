package com.driftwatch.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Canonical digest of an ingest request (guide 4.2): the same content produces the same digest
 * regardless of JSON key order, so an Idempotency-Key replay can be recognised while different
 * content under the same key is rejected with 409.
 */
@Component
public class RequestDigests {

    private final ObjectMapper canonicalMapper = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    public String of(DataEvent event) {
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("event_id", event.eventId());
        material.put("source", event.source());
        material.put("event_type", event.eventType());
        material.put("event_timestamp", event.eventTimestamp() == null ? null : event.eventTimestamp().toString());
        material.put("payload", event.payload());
        return sha256(material);
    }

    public String sha256(Object value) {
        try {
            byte[] canonical = canonicalMapper.writeValueAsBytes(value);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (Exception e) {
            throw new IllegalStateException("Unable to digest request", e);
        }
    }

    public String sha256Text(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Unable to digest request", e);
        }
    }
}
