package com.driftwatch.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

/** SHA-256 of a canonical (sorted-key) JSON representation of an event payload. */
@Component
public class PayloadHasher {

    private final ObjectMapper canonicalMapper;

    public PayloadHasher() {
        this.canonicalMapper = new ObjectMapper()
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    public String hash(Map<String, Object> payload) {
        try {
            byte[] canonical = canonicalMapper.writeValueAsBytes(payload == null ? Map.of() : payload);
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha.digest(canonical));
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("Unable to hash payload", e);
        }
    }
}
