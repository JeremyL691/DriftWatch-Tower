package com.driftwatch.source.github;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

/**
 * One HTTP outcome from the GitHub events API (execution guide, section 6.3). The status decides
 * the poller's next state: QUIET for 304, BACKOFF for rate limiting or server errors, ERROR for
 * configuration problems.
 */
public record GithubFetchResult(
        Status status,
        String etag,
        Integer pollIntervalSeconds,
        Integer retryAfterSeconds,
        Instant rateLimitReset,
        Integer rateLimitRemaining,
        String bodyDigest,
        JsonNode body,
        String failureReason
) {

    public enum Status {
        /** 200 with a parsable array. */
        OK,
        /** 304: nothing changed; the event cursor must not advance. */
        NOT_MODIFIED,
        /** 403/429 with rate-limit information. */
        RATE_LIMITED,
        /** 401: the configured token is invalid; never echoed back. */
        UNAUTHORIZED,
        /** 404: the repository or endpoint does not exist. */
        NOT_FOUND,
        /** 5xx. */
        SERVER_ERROR,
        /** Connect or read timeout. */
        TIMEOUT,
        /** Any other status or an unparsable body. */
        FAILED
    }

    public static GithubFetchResult notModified(String etag, Integer pollInterval) {
        return new GithubFetchResult(Status.NOT_MODIFIED, etag, pollInterval, null, null, null, null, null, null);
    }
}
