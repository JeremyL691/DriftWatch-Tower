package com.driftwatch.dlt;

import com.driftwatch.config.KafkaTopics;
import com.driftwatch.event.RawEnvelope;
import com.driftwatch.persistence.DeadLetterRecordEntity;
import com.driftwatch.persistence.DeadLetterRecordRepository;
import com.driftwatch.persistence.DeadLetterReplayEntity;
import com.driftwatch.persistence.DeadLetterReplayRepository;
import com.driftwatch.stream.ProcessedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Operator surface for dead letters (guide 4.5 and 7.1).
 *
 * <p>A replay re-enters the pipeline at the stage where the record failed, keeping the original
 * ingestion identity and mode: a SINK failure is re-published as the original ProcessedEvent to
 * {@code quality-events-v1} (the sink's receipt keeps it idempotent), while STREAM/SOURCE failures
 * are re-published as the original envelope to {@code raw-events-v1}. Every attempt is recorded
 * with a fresh replay_attempt_id, and repeating a replay cannot add a second set of side effects.
 */
@Service
public class DeadLetterService {

    private final DeadLetterRecordRepository recordRepository;
    private final DeadLetterReplayRepository replayRepository;
    private final KafkaTemplate<String, ProcessedEvent> processedTemplate;
    private final KafkaTemplate<String, RawEnvelope> rawTemplate;
    private final ObjectMapper objectMapper;

    public DeadLetterService(DeadLetterRecordRepository recordRepository,
                             DeadLetterReplayRepository replayRepository,
                             KafkaTemplate<String, ProcessedEvent> processedTemplate,
                             KafkaTemplate<String, RawEnvelope> rawTemplate,
                             ObjectMapper objectMapper) {
        this.recordRepository = recordRepository;
        this.replayRepository = replayRepository;
        this.processedTemplate = processedTemplate;
        this.rawTemplate = rawTemplate;
        this.objectMapper = objectMapper;
    }

    public List<DeadLetterRecordEntity> list(String stage, String state, int page, int size) {
        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100));
        if (stage != null && state != null) {
            return recordRepository.findByStageAndRecoveryStateOrderByIdAsc(stage, state, pageable).getContent();
        }
        if (stage != null) {
            return recordRepository.findByStageOrderByIdAsc(stage, pageable).getContent();
        }
        if (state != null) {
            return recordRepository.findByRecoveryStateOrderByIdAsc(state, pageable).getContent();
        }
        return recordRepository.findAll(pageable).getContent();
    }

    public DeadLetterRecordEntity get(long id) {
        return recordRepository.findById(id).orElse(null);
    }

    public List<DeadLetterReplayEntity> replayHistory(long id) {
        return replayRepository.findByDeadLetterIdOrderByIdAsc(id);
    }

    /** Result of one replay attempt. */
    public record ReplayResult(String replayAttemptId, String stage, String outcome, String detail) {}

    @Transactional
    public ReplayResult replay(long id) {
        DeadLetterRecordEntity record = recordRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("dead letter " + id + " does not exist"));
        String attemptId = UUID.randomUUID().toString();
        String outcome;
        String detail;
        try {
            boolean published = republish(record);
            if (published) {
                outcome = "PUBLISHED";
                detail = "re-published to " + targetTopic(record.getStage())
                        + " keeping ingestion identity " + record.getIngestionId();
                record.setRecoveryState(DeadLetterRecordEntity.STATE_REPLAYED);
                record.setRecoveredAt(Instant.now());
            } else {
                outcome = "FAILED";
                detail = "broker did not acknowledge the replay within the timeout";
            }
        } catch (Exception e) {
            outcome = "FAILED";
            detail = DltMessage.truncate(e.getClass().getSimpleName() + ": " + e.getMessage(),
                    DltMessage.MAX_REASON_LENGTH);
        }
        DeadLetterReplayEntity replay = new DeadLetterReplayEntity();
        replay.setDeadLetterId(record.getId());
        replay.setReplayAttemptId(attemptId);
        replay.setStage(record.getStage());
        replay.setOutcome(outcome);
        replay.setDetail(detail);
        replay.setRequestedAt(Instant.now());
        replayRepository.save(replay);
        if ("PUBLISHED".equals(outcome)) {
            record.setAttempts(record.getAttempts() + 1);
            recordRepository.save(record);
        }
        return new ReplayResult(attemptId, record.getStage(), outcome, detail);
    }

    private boolean republish(DeadLetterRecordEntity record) throws Exception {
        String payload = record.getPayload() == null ? null : record.getPayload().path("raw").asText(null);
        if (payload == null || payload.isBlank()) {
            throw new IllegalStateException("dead letter has no stored payload to replay");
        }
        String stage = record.getStage() == null ? DltStage.STREAM.name() : record.getStage();
        if (DltStage.SINK.name().equals(stage)) {
            ProcessedEvent processed = objectMapper.readValue(payload, ProcessedEvent.class);
            processedTemplate.send(KafkaTopics.QUALITY_EVENTS_V1, processed.event().source(), processed)
                    .get(10, TimeUnit.SECONDS);
            return true;
        }
        RawEnvelope envelope = objectMapper.readValue(payload, RawEnvelope.class);
        rawTemplate.send(KafkaTopics.RAW_EVENTS_V1,
                        com.driftwatch.quality.ScopeKey.of(envelope.event().source(), envelope.event().eventType()),
                        envelope)
                .get(10, TimeUnit.SECONDS);
        return true;
    }

    private String targetTopic(String stage) {
        return DltStage.SINK.name().equals(stage) ? KafkaTopics.QUALITY_EVENTS_V1 : KafkaTopics.RAW_EVENTS_V1;
    }
}
