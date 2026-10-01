package com.driftwatch.config;

import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Short-lived tickets for the dashboard WebSocket (execution guide, section 7.5).
 *
 * <p>Browsers do not replay HTTP Basic credentials on a WebSocket handshake, so the dashboard
 * fetches a ticket from an authenticated endpoint and presents it as a query parameter. The
 * ticket is an HMAC over the user name and expiry with a per-process random secret and lives for
 * one minute; it is not a session and cannot be reused after expiry.
 */
@Service
public class WsTicketService {

    public static final Duration TTL = Duration.ofMinutes(1);

    private final Clock clock;
    private final byte[] secret = new byte[32];

    public WsTicketService(Clock clock) {
        this.clock = clock;
        new SecureRandom().nextBytes(secret);
    }

    /** Issues a ticket for the authenticated principal. */
    public String issue(String username) {
        long expiresAt = clock.instant().plus(TTL).getEpochSecond();
        String payload = username + "|" + expiresAt;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8))
                + "." + sign(payload);
    }

    /** @return the user name when the ticket is valid and unexpired, otherwise null */
    public String verify(String ticket) {
        if (ticket == null || ticket.isBlank()) {
            return null;
        }
        int dot = ticket.lastIndexOf('.');
        if (dot <= 0 || dot == ticket.length() - 1) {
            return null;
        }
        String payload;
        try {
            payload = new String(Base64.getUrlDecoder().decode(ticket.substring(0, dot)), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
        String signature = ticket.substring(dot + 1);
        if (!java.security.MessageDigest.isEqual(sign(payload).getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8))) {
            return null;
        }
        int separator = payload.lastIndexOf('|');
        if (separator <= 0) {
            return null;
        }
        String username = payload.substring(0, separator);
        try {
            long expiresAt = Long.parseLong(payload.substring(separator + 1));
            return Instant.ofEpochSecond(expiresAt).isAfter(clock.instant()) ? username : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String sign(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC unavailable", e);
        }
    }
}
