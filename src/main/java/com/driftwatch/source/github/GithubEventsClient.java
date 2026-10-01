package com.driftwatch.source.github;

import com.driftwatch.config.DriftwatchProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Read-only client for the official GitHub events API (execution guide, section 6.1).
 *
 * <p>Sends the documented headers, honours ETag and {@code X-Poll-Interval}, and never performs a
 * write request. A 304 means "no change" and never advances the event cursor. Rate-limit
 * responses carry {@code Retry-After} or the rate-limit reset so the poller can back off instead
 * of hammering the API. The optional token is read from configuration and never logged.
 */
@Component
public class GithubEventsClient {

    private static final Logger log = LoggerFactory.getLogger(GithubEventsClient.class);

    public static final String API_VERSION = "2026-03-10";

    private final DriftwatchProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public GithubEventsClient(DriftwatchProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        Duration connectTimeout = properties.source().github().connectTimeout();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /** Fetches one page; {@code etag} is the last applied validator for page 1. */
    public GithubFetchResult fetchPage(String url, String etag) {
        DriftwatchProperties.Source.Github config = properties.source().github();
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(config.requestTimeout())
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", API_VERSION)
                .header("User-Agent", "DriftWatch-Tower/" + userAgentVersion())
                .GET();
        if (etag != null && !etag.isBlank()) {
            builder.header("If-None-Match", etag);
        }
        if (config.token() != null && !config.token().isBlank()) {
            builder.header("Authorization", "Bearer " + config.token());
        }

        HttpResponse<String> response;
        try {
            response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (java.net.http.HttpTimeoutException e) {
            return failure(GithubFetchResult.Status.TIMEOUT, "request timed out after " + config.requestTimeout());
        } catch (Exception e) {
            return failure(GithubFetchResult.Status.TIMEOUT, e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        String responseEtag = header(response, "etag").orElse(null);
        Integer pollInterval = header(response, "x-poll-interval").flatMap(GithubEventsClient::parseInt).orElse(null);
        Integer retryAfter = header(response, "retry-after").flatMap(GithubEventsClient::parseInt).orElse(null);
        Instant rateLimitReset = header(response, "x-ratelimit-reset")
                .flatMap(GithubEventsClient::parseLong).map(Instant::ofEpochSecond).orElse(null);
        Integer remaining = header(response, "x-ratelimit-remaining").flatMap(GithubEventsClient::parseInt).orElse(null);
        String digest = sha256(response.body());

        return switch (response.statusCode()) {
            case 200 -> parseBody(response.body(), responseEtag, pollInterval, remaining, digest);
            case 304 -> GithubFetchResult.notModified(responseEtag, pollInterval);
            case 401 -> new GithubFetchResult(GithubFetchResult.Status.UNAUTHORIZED, responseEtag, pollInterval,
                    retryAfter, rateLimitReset, remaining, digest, null,
                    "token rejected; check the configured credential (value not logged)");
            case 403, 429 -> new GithubFetchResult(GithubFetchResult.Status.RATE_LIMITED, responseEtag, pollInterval,
                    retryAfter, rateLimitReset, remaining, digest, null,
                    "rate limited (remaining=" + remaining + ")");
            case 404 -> new GithubFetchResult(GithubFetchResult.Status.NOT_FOUND, responseEtag, pollInterval,
                    retryAfter, rateLimitReset, remaining, digest, null, "endpoint or repository not found");
            default -> response.statusCode() >= 500
                    ? new GithubFetchResult(GithubFetchResult.Status.SERVER_ERROR, responseEtag, pollInterval,
                            retryAfter, rateLimitReset, remaining, digest, null,
                            "upstream status " + response.statusCode())
                    : new GithubFetchResult(GithubFetchResult.Status.FAILED, responseEtag, pollInterval,
                            retryAfter, rateLimitReset, remaining, digest, null,
                            "unexpected status " + response.statusCode());
        };
    }

    private GithubFetchResult parseBody(String body, String etag, Integer pollInterval,
                                        Integer remaining, String digest) {
        try {
            JsonNode parsed = objectMapper.readTree(body);
            if (!parsed.isArray()) {
                return new GithubFetchResult(GithubFetchResult.Status.FAILED, etag, pollInterval, null, null,
                        remaining, digest, null, "response body is not an array");
            }
            return new GithubFetchResult(GithubFetchResult.Status.OK, etag, pollInterval, null, null,
                    remaining, digest, parsed, null);
        } catch (Exception e) {
            return new GithubFetchResult(GithubFetchResult.Status.FAILED, etag, pollInterval, null, null,
                    remaining, digest, null, "response body could not be parsed: " + e.getClass().getSimpleName());
        }
    }

    private static GithubFetchResult failure(GithubFetchResult.Status status, String reason) {
        log.warn("github fetch failed: {} ({})", status, reason);
        return new GithubFetchResult(status, null, null, null, null, null, null, null, reason);
    }

    private static Optional<String> header(HttpResponse<?> response, String name) {
        return response.headers().firstValue(name);
    }

    private static Optional<Integer> parseInt(String value) {
        try {
            return Optional.of(Integer.parseInt(value.trim()));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static Optional<Long> parseLong(String value) {
        try {
            return Optional.of(Long.parseLong(value.trim()));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private String userAgentVersion() {
        return properties.source().github().userAgentVersion();
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
