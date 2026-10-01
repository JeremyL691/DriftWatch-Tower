package com.driftwatch.event;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.UUID;

/**
 * Internal versioned envelope for the raw pipeline (execution guide, section 4.1).
 *
 * <p>One logical ingestion has exactly one {@code ingestionId}; publish retries and operational
 * replays keep it, so a Kafka redelivery can be recognised and cannot add detection counts a
 * second time. {@code receivedAt} is the first time this application accepted the event and is
 * not changed by retries. The business identity stays in {@link DataEvent#eventId()}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RawEnvelope(
        @JsonProperty("contract_version")
        @Min(1) int contractVersion,
        @JsonProperty("ingestion_id")
        @NotNull UUID ingestionId,
        @JsonProperty("event")
        @NotNull @Valid DataEvent event,
        @JsonProperty("received_at")
        @NotNull Instant receivedAt,
        @JsonProperty("origin")
        @NotNull Origin origin,
        @JsonProperty("mode")
        @NotNull Mode mode,
        /** GitHub event id, or Kafka topic/partition/offset for migrated records. Never a secret. */
        @JsonProperty("origin_reference")
        String originReference,
        /** Optional operator replay source; a replay never replaces the ingestion id. */
        @JsonProperty("replay_of")
        String replayOf
) {

    public static final int CONTRACT_VERSION = 1;

    public enum Origin { REST, GITHUB, LEGACY }

    /** BOOTSTRAP/REPLAY/SYNTHETIC do not participate in live windows unless explicitly enabled. */
    public enum Mode { LIVE, BOOTSTRAP, REPLAY, SYNTHETIC }

    public static RawEnvelope forRest(DataEvent event, Instant receivedAt) {
        return new RawEnvelope(CONTRACT_VERSION, UUID.randomUUID(), event, receivedAt,
                Origin.REST, Mode.LIVE, null, null);
    }

    public boolean liveWindowEligible() {
        return mode == Mode.LIVE;
    }
}