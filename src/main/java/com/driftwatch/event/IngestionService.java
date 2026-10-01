package com.driftwatch.event;

import com.driftwatch.persistence.IngestionReceiptEntity;
import com.driftwatch.persistence.IngestionReceiptRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE;

/**
 * Ingest contract for the REST API (execution guide, section 4.2).
 *
 * <ul>
 *   <li>one logical ingestion gets one {@code ingestion_id}; the caller sees 202 only after the
 *       broker acknowledged the record, otherwise 503 with the idempotency retry hint</li>
 *   <li>an Idempotency-Key reserves the identity in its own transaction before publishing, so a
 *       restart cannot allocate a second identity; the same key with different content is 409</li>
 *   <li>batches validate every item before publishing anything and report accepted/failed
 *       indexes with per-item ingestion ids</li>
 * </ul>
 */
@Service
public class IngestionService {

    /** Receipts expire with the documented 24 hour idempotency window. */
    public static final Duration IDEMPOTENCY_WINDOW = Duration.ofHours(24);
    public static final int BATCH_MAX_ITEMS = 100;
    private static final int MAX_EVENT_BYTES = 256 * 1024;

    private final RawEventProducer producer;
    private final IngestionReceiptRepository receiptRepository;
    private final RequestDigests digests;
    private final ObjectMapper objectMapper;

    public IngestionService(RawEventProducer producer,
                            IngestionReceiptRepository receiptRepository,
                            RequestDigests digests,
                            ObjectMapper objectMapper) {
        this.producer = producer;
        this.receiptRepository = receiptRepository;
        this.digests = digests;
        this.objectMapper = objectMapper;
    }

    /** Outcome of one accepted (or already known) ingestion. */
    public record Acceptance(String ingestionId, String eventId, boolean confirmed, boolean replayed) {}

    public record BatchOutcome(String batchId,
                               List<Acceptance> accepted,
                               List<Integer> failedIndexes,
                               boolean allFailed) {}

    public Acceptance ingest(DataEvent event, String idempotencyKey) {
        rejectOversized(event);
        RawEnvelope envelope = envelope(event, null);
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            boolean confirmed = producer.publishAndAwait(envelope);
            if (!confirmed) {
                throw new ResponseStatusException(SERVICE_UNAVAILABLE,
                        "broker acknowledgement timed out; retry with an Idempotency-Key to keep the same identity");
            }
            return new Acceptance(envelope.ingestionId().toString(), event.eventId(), true, false);
        }

        String digest = digests.of(event);
        Reservation reservation = reserve(event, idempotencyKey, digest, envelope.ingestionId().toString());
        if (reservation.replayed()) {
            return new Acceptance(reservation.ingestionId(), event.eventId(),
                    IngestionReceiptEntity.STATE_CONFIRMED.equals(reservation.state()), true);
        }
        boolean confirmed = producer.publishAndAwait(envelope);
        markState(event, idempotencyKey, confirmed
                ? IngestionReceiptEntity.STATE_CONFIRMED
                : IngestionReceiptEntity.STATE_FAILED);
        if (!confirmed) {
            throw new ResponseStatusException(SERVICE_UNAVAILABLE,
                    "broker acknowledgement timed out; retry with the same Idempotency-Key to keep identity "
                            + reservation.ingestionId());
        }
        return new Acceptance(reservation.ingestionId(), event.eventId(), true, false);
    }

    public BatchOutcome ingestBatch(List<DataEvent> events, String idempotencyKey) {
        if (events == null || events.isEmpty()) {
            throw new IllegalArgumentException("batch must contain at least one event");
        }
        if (events.size() > BATCH_MAX_ITEMS) {
            throw new IllegalArgumentException("batch may contain at most " + BATCH_MAX_ITEMS + " events");
        }
        events.forEach(this::rejectOversized);

        String batchDigest = idempotencyKey == null || idempotencyKey.isBlank()
                ? null
                : digests.sha256(events.stream().map(digests::of).toList());
        String batchId = UUID.randomUUID().toString();

        List<Acceptance> accepted = new ArrayList<>();
        List<Integer> failed = new ArrayList<>();
        for (int index = 0; index < events.size(); index++) {
            DataEvent event = events.get(index);
            RawEnvelope envelope = envelope(event, batchDigest);
            if (batchDigest != null) {
                // Per-item receipts share the batch key through source/event_type/idempotency key.
                Optional<IngestionReceiptEntity> existing =
                        receiptRepository.findById(receiptKey(event, idempotencyKey));
                if (existing.isPresent()) {
                    IngestionReceiptEntity receipt = existing.get();
                    if (!receipt.getRequestDigest().equals(digests.of(event))) {
                        throw new ResponseStatusException(CONFLICT,
                                "Idempotency-Key was already used for different content");
                    }
                    accepted.add(new Acceptance(receipt.getIngestionId(), event.eventId(),
                            IngestionReceiptEntity.STATE_CONFIRMED.equals(receipt.getPublishState()), true));
                    continue;
                }
            }
            if (!producer.publishAndAwait(envelope)) {
                failed.add(index);
                continue;
            }
            if (batchDigest != null) {
                saveBatchItem(event, idempotencyKey, envelope, batchId);
            }
            accepted.add(new Acceptance(envelope.ingestionId().toString(), event.eventId(), true, false));
        }

        // A retried batch loses the failed indexes once everything is confirmed.
        if (batchDigest != null) {
            updateBatchReceipt(events, idempotencyKey, batchId, failed);
        }
        return new BatchOutcome(batchId, accepted, failed, accepted.isEmpty());
    }

    // ------------------------------------------------------------------ receipts

    public record Reservation(String ingestionId, String state, boolean replayed) {}

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Reservation reserve(DataEvent event, String idempotencyKey, String digest, String newIngestionId) {
        IngestionReceiptEntity.Key key = receiptKey(event, idempotencyKey);
        Optional<IngestionReceiptEntity> existing = receiptRepository.findById(key);
        if (existing.isPresent()) {
            IngestionReceiptEntity receipt = existing.get();
            if (!receipt.getRequestDigest().equals(digest)) {
                throw new ResponseStatusException(CONFLICT,
                        "Idempotency-Key was already used for different content");
            }
            return new Reservation(receipt.getIngestionId(), receipt.getPublishState(), true);
        }
        IngestionReceiptEntity receipt = new IngestionReceiptEntity();
        receipt.setSource(event.source());
        receipt.setEventType(event.eventType());
        receipt.setIdempotencyKey(idempotencyKey);
        receipt.setRequestDigest(digest);
        receipt.setIngestionId(newIngestionId);
        receipt.setEventId(event.eventId());
        receipt.setPublishState(IngestionReceiptEntity.STATE_PENDING);
        receipt.setCreatedAt(Instant.now());
        receipt.setExpiresAt(Instant.now().plus(IDEMPOTENCY_WINDOW));
        receiptRepository.save(receipt);
        return new Reservation(newIngestionId, IngestionReceiptEntity.STATE_PENDING, false);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markState(DataEvent event, String idempotencyKey, String state) {
        receiptRepository.findById(receiptKey(event, idempotencyKey)).ifPresent(receipt -> {
            receipt.setPublishState(state);
            receiptRepository.save(receipt);
        });
    }

    private void saveBatchItem(DataEvent event, String idempotencyKey, RawEnvelope envelope, String batchId) {
        IngestionReceiptEntity.Key key = receiptKey(event, idempotencyKey);
        IngestionReceiptEntity receipt = receiptRepository.findById(key).orElseGet(IngestionReceiptEntity::new);
        receipt.setSource(event.source());
        receipt.setEventType(event.eventType());
        receipt.setIdempotencyKey(idempotencyKey);
        receipt.setRequestDigest(digests.of(event));
        receipt.setIngestionId(envelope.ingestionId().toString());
        receipt.setEventId(event.eventId());
        receipt.setPublishState(IngestionReceiptEntity.STATE_CONFIRMED);
        receipt.setBatch(true);
        ObjectNode item = objectMapper.createObjectNode();
        item.put("batch_id", batchId);
        item.put("event_id", event.eventId());
        item.put("ingestion_id", envelope.ingestionId().toString());
        receipt.setBatchItems(item);
        receipt.setCreatedAt(Instant.now());
        receipt.setExpiresAt(Instant.now().plus(IDEMPOTENCY_WINDOW));
        receiptRepository.save(receipt);
    }

    private void updateBatchReceipt(List<DataEvent> events, String idempotencyKey, String batchId,
                                    List<Integer> failedIndexes) {
        ArrayNode items = objectMapper.createArrayNode();
        for (int index = 0; index < events.size(); index++) {
            ObjectNode item = objectMapper.createObjectNode();
            item.put("index", index);
            item.put("event_id", events.get(index).eventId());
            item.put("status", failedIndexes.contains(index) ? "FAILED" : "CONFIRMED");
            items.add(item);
        }
        DataEvent first = events.get(0);
        receiptRepository.findById(receiptKey(first, idempotencyKey)).ifPresent(receipt -> {
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("batch_id", batchId);
            payload.set("items", items);
            receipt.setBatch(true);
            receipt.setBatchItems(payload);
            receiptRepository.save(receipt);
        });
    }

    private IngestionReceiptEntity.Key receiptKey(DataEvent event, String idempotencyKey) {
        return new IngestionReceiptEntity.Key(event.source(), event.eventType(), idempotencyKey);
    }

    // ------------------------------------------------------------------ helpers

    private RawEnvelope envelope(DataEvent event, String replayOf) {
        return new RawEnvelope(RawEnvelope.CONTRACT_VERSION, UUID.randomUUID(), event, Instant.now(),
                RawEnvelope.Origin.REST, RawEnvelope.Mode.LIVE, null, replayOf);
    }

    private void rejectOversized(DataEvent event) {
        try {
            int size = objectMapper.writeValueAsBytes(event).length;
            if (size > MAX_EVENT_BYTES) {
                throw new IllegalArgumentException("event exceeds the 256 KiB limit");
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("event could not be serialised", e);
        }
    }
}