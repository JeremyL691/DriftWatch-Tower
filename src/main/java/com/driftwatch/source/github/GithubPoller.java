package com.driftwatch.source.github;

import com.driftwatch.config.DriftwatchProperties;
import com.driftwatch.config.KafkaTopics;
import com.driftwatch.event.DataEvent;
import com.driftwatch.event.RawEnvelope;
import com.driftwatch.persistence.CollectorStateEntity;
import com.driftwatch.persistence.CollectorStateRepository;
import com.driftwatch.persistence.SourceGapEntity;
import com.driftwatch.persistence.SourceGapRepository;
import com.driftwatch.persistence.SourceInboxEntity;
import com.driftwatch.persistence.SourceInboxRepository;
import com.driftwatch.persistence.SourceOutboxEntity;
import com.driftwatch.persistence.SourceOutboxRepository;
import com.driftwatch.persistence.SourcePollRunEntity;
import com.driftwatch.persistence.SourcePollRunRepository;
import com.driftwatch.operations.DriftwatchMetrics;
import com.driftwatch.quality.ScopeKey;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * GitHub public-event poller (execution guide, sections 6.2 and 6.3).
 *
 * <p>One round is: acquire the single lease, fetch page 1 with the applied ETag, traverse up to
 * {@code max-pages} while the pages still contain unknown ids, then stage every new record in one
 * transaction (inbox row + outbox row + stable ingestion id) and let the relay publish it. The
 * candidate ETag only becomes the applied checkpoint once every staged record has a processing
 * receipt, so a crash between publish and checkpoint resumes instead of skipping data. A 304
 * updates poll health only and never advances the event cursor.
 */
@Component
@ConditionalOnProperty(name = "driftwatch.source.github.enabled", havingValue = "true")
public class GithubPoller {

    private static final Logger log = LoggerFactory.getLogger(GithubPoller.class);

    private static final Duration MIN_POLL_INTERVAL = Duration.ofMinutes(5);
    private static final Duration RATE_LIMIT_BACKOFF_MIN = Duration.ofSeconds(60);
    private static final Duration RATE_LIMIT_BACKOFF_MAX = Duration.ofHours(1);
    private static final Duration SERVER_BACKOFF_MIN = Duration.ofSeconds(5);
    private static final Duration SERVER_BACKOFF_MAX = Duration.ofMinutes(5);
    private static final Duration LEASE_DURATION = Duration.ofMinutes(2);
    static final int RELAY_BATCH_SIZE = 20;

    private final DriftwatchProperties properties;
    private final GithubEventsClient client;
    private final GithubEventConverter converter;
    private final CollectorStateRepository stateRepository;
    private final SourceInboxRepository inboxRepository;
    private final SourceOutboxRepository outboxRepository;
    private final SourcePollRunRepository pollRunRepository;
    private final SourceGapRepository gapRepository;
    private final KafkaTemplate<String, RawEnvelope> rawTemplate;
    private final com.driftwatch.persistence.ProcessedReceiptRepository receiptRepository;
    private final com.driftwatch.persistence.DeadLetterRecordRepository deadLetterRepository;
    private final org.springframework.transaction.support.TransactionTemplate transactionTemplate;
    private final DriftwatchMetrics metrics;

    public GithubPoller(DriftwatchProperties properties,
                        GithubEventsClient client,
                        GithubEventConverter converter,
                        CollectorStateRepository stateRepository,
                        SourceInboxRepository inboxRepository,
                        SourceOutboxRepository outboxRepository,
                        SourcePollRunRepository pollRunRepository,
                        SourceGapRepository gapRepository,
                        KafkaTemplate<String, RawEnvelope> rawTemplate,
                        com.driftwatch.persistence.ProcessedReceiptRepository receiptRepository,
                        com.driftwatch.persistence.DeadLetterRecordRepository deadLetterRepository,
                        org.springframework.transaction.PlatformTransactionManager transactionManager,
                        DriftwatchMetrics metrics) {
        this.properties = properties;
        this.client = client;
        this.converter = converter;
        this.stateRepository = stateRepository;
        this.inboxRepository = inboxRepository;
        this.outboxRepository = outboxRepository;
        this.pollRunRepository = pollRunRepository;
        this.gapRepository = gapRepository;
        this.rawTemplate = rawTemplate;
        this.receiptRepository = receiptRepository;
        this.deadLetterRepository = deadLetterRepository;
        this.transactionTemplate = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        this.metrics = metrics;
    }

    /**
     * Runs one round immediately, ignoring the schedule (used by tests and the acceptance smoke).
     * The lease and every other guard still apply.
     */
    public void pollNow() {
        DriftwatchProperties.Source.Github config = properties.source().github();
        String source = config.sourceId();
        Instant now = Instant.now();
        CollectorStateEntity state = stateRepository.findById(source)
                .orElseGet(() -> initialState(source, now));
        if (state.getPendingPollRunId() != null) {
            relayPending(source);
            promoteIfComplete(source, state, now);
            return;
        }
        if (!acquireLease(state, now)) {
            return;
        }
        try {
            runRound(config, state, now);
        } finally {
            releaseLease(state, Instant.now());
        }
    }

    /** Poll ticks; a round only starts when the lease is free and the next poll time has passed. */
    @Scheduled(fixedDelay = 15_000L, initialDelay = 20_000L)
    public void tick() {
        DriftwatchProperties.Source.Github config = properties.source().github();
        if (!config.enabled() || !config.schedulerEnabled()) {
            return;
        }
        String source = config.sourceId();
        Instant now = Instant.now();
        CollectorStateEntity state = stateRepository.findById(source)
                .orElseGet(() -> initialState(source, now));

        // Recover an unfinished round before requesting anything new (guide 6.3 step 6).
        if (state.getPendingPollRunId() != null) {
            relayPending(source);
            promoteIfComplete(source, state, now);
            return;
        }
        if (state.getNextPollAt() != null && now.isBefore(state.getNextPollAt())) {
            return;
        }
        if (!acquireLease(state, now)) {
            log.debug("poll lease for {} is held by {} until {}", source, state.getLeaseOwner(),
                    state.getLeaseExpiresAt());
            return;
        }
        try {
            runRound(config, state, now);
        } catch (Exception e) {
            log.error("poll round for {} failed unexpectedly: {}", source, e.toString());
            applyBackoff(state, SERVER_BACKOFF_MIN, "unexpected: " + e.getClass().getSimpleName(), now);
        } finally {
            releaseLease(state, Instant.now());
        }
    }

    // ------------------------------------------------------------------ round

    private void runRound(DriftwatchProperties.Source.Github config, CollectorStateEntity state, Instant now) {
        String source = config.sourceId();
        boolean bootstrap = state.getEtagApplied() == null && inboxRepository.countBySource(source) == 0;
        state.setStatus(CollectorStateEntity.STATUS_FETCHING);
        state.setLastPollAt(now);
        state.setUpdatedAt(now);
        stateRepository.save(state);

        metrics.recordCollectorPoll("started");
        SourcePollRunEntity run = new SourcePollRunEntity();
        run.setSource(source);
        run.setStatus(SourcePollRunEntity.STATUS_RUNNING);
        run.setMode(bootstrap ? RawEnvelope.Mode.BOOTSTRAP.name() : RawEnvelope.Mode.LIVE.name());
        run.setStartedAt(now);
        pollRunRepository.save(run);

        GithubFetchResult first = client.fetchPage(config.eventsUrl(config.pageSize()), state.getEtagApplied());
        if (first.status() == GithubFetchResult.Status.NOT_MODIFIED) {
            finishQuiet(state, run, first, now);
            return;
        }
        if (first.status() != GithubFetchResult.Status.OK) {
            handleFailure(state, run, first, now);
            return;
        }

        List<JsonNode> records = new ArrayList<>(first.body() == null ? List.of() : toList(first.body()));
        int pagesRead = 1;
        boolean truncated = false;
        while (pagesRead < config.maxPages()) {
            List<JsonNode> page = recordsOfLastPage(records, config.pageSize(), pagesRead);
            if (page.size() < config.pageSize() || allKnown(source, page)) {
                break;
            }
            if (bootstrap && records.size() >= config.maxFirstReadRecords()) {
                truncated = true;
                break;
            }
            pagesRead++;
            GithubFetchResult next = client.fetchPage(
                    config.eventsUrlForPage(pagesRead, config.pageSize()), null);
            if (next.status() != GithubFetchResult.Status.OK) {
                // A failed later page keeps the records already read; the checkpoint is not applied.
                recordGap(source, run.getId(), "PAGINATION_INCOMPLETE",
                        null, null, "page " + pagesRead + " failed: " + next.failureReason());
                truncated = true;
                break;
            }
            records.addAll(toList(next.body()));
        }
        records.sort(GithubEventConverter::compareForOrdering);

        StageResult staged = stage(source, run, records, first, config);
        if (truncated) {
            recordGap(source, run.getId(), "PAGINATION_TRUNCATED",
                    staged.oldestCreatedAt(), staged.newestCreatedAt(),
                    "stopped after " + pagesRead + " pages; later records are not yet visible");
        }
        detectVisibilityGaps(state, run, staged, now);
        if (first.rateLimitRemaining() != null && first.rateLimitRemaining() <= 0) {
            recordGap(source, run.getId(), "BUDGET_EXHAUSTED", staged.oldestCreatedAt(),
                    staged.newestCreatedAt(), "rate-limit budget exhausted; the round stays pending");
        }

        relayPending(source);
        promoteIfComplete(source, state, now);
    }

    private record StageResult(int newRecords, int seenRecords, Instant oldestCreatedAt,
                               Instant newestCreatedAt, Long runId) {}

    /**
     * Stages one round in a single transaction: inbox rows, ingestion ids and outbox rows commit
     * together (guide 6.3 step 3). The transaction is explicit because the caller is in the same
     * bean, so an annotation on this method would be bypassed.
     */
    StageResult stage(String source, SourcePollRunEntity run, List<JsonNode> records,
                      GithubFetchResult first, DriftwatchProperties.Source.Github config) {
        return transactionTemplate.execute(status -> stageInTransaction(source, run, records, first, config));
    }

    private StageResult stageInTransaction(String source, SourcePollRunEntity run, List<JsonNode> records,
                                           GithubFetchResult first, DriftwatchProperties.Source.Github config) {
        Instant now = Instant.now();
        int newRecords = 0;
        Instant oldest = null;
        Instant newest = null;
        for (JsonNode raw : records) {
            GithubEventConverter.Converted converted = converter.convert(raw, config.repository());
            if (oldest == null || converted.createdAt().isBefore(oldest)) {
                oldest = converted.createdAt();
            }
            if (newest == null || converted.createdAt().isAfter(newest)) {
                newest = converted.createdAt();
            }
            if (inboxRepository.existsBySourceAndGithubEventId(source, converted.githubEventId())) {
                continue;
            }
            SourceInboxEntity inbox = new SourceInboxEntity();
            inbox.setSource(source);
            inbox.setGithubEventId(converted.githubEventId());
            inbox.setEventType(converted.eventType());
            inbox.setCreatedAt(converted.createdAt());
            inbox.setIngestionId(UUID.randomUUID().toString());
            inbox.setMode(run.getMode());
            inbox.setOriginReference("github:" + config.repository() + "#" + converted.githubEventId()
                    + "@" + run.getId());
            inbox.setPayload(converted.payload());
            inbox.setContentHash(converted.contentHash());
            inbox.setPollRunId(run.getId());
            inbox.setReceivedAt(now);
            inboxRepository.save(inbox);

            SourceOutboxEntity outbox = new SourceOutboxEntity();
            outbox.setSource(source);
            outbox.setIngestionId(inbox.getIngestionId());
            outbox.setInboxId(inbox.getId());
            outbox.setStatus(SourceOutboxEntity.STATUS_PENDING);
            outbox.setCreatedAt(now);
            outboxRepository.save(outbox);
            newRecords++;
        }
        run.setStatus(SourcePollRunEntity.STATUS_STAGED);
        run.setEtagCandidate(first.etag());
        run.setPagesRead(Math.max(1, (records.size() + config.pageSize() - 1) / config.pageSize()));
        run.setRecordsSeen(records.size());
        run.setNewRecords(newRecords);
        run.setOldestCreatedAt(oldest);
        run.setNewestCreatedAt(newest);
        run.setXPollInterval(first.pollIntervalSeconds());
        run.setFinishedAt(now);
        pollRunRepository.save(run);

        CollectorStateEntity state = stateRepository.findById(source).orElseThrow();
        state.setStatus(CollectorStateEntity.STATUS_STAGED);
        state.setEtagCandidate(first.etag());
        state.setPendingPollRunId(run.getId());
        state.setLastPollSuccess(now);
        state.setConsecutiveFailures(0);
        state.setLastError(null);
        state.setUpdatedAt(now);
        stateRepository.save(state);
        return new StageResult(newRecords, records.size(), oldest, newest, run.getId());
    }

    // ------------------------------------------------------------------ relay and promotion

    /** Publishes pending outbox rows in small batches and marks them SENT after the ack. */
    public int relayPending(String source) {
        List<SourceOutboxEntity> pending = outboxRepository.findBySourceAndStatusOrderByIdAsc(
                source, SourceOutboxEntity.STATUS_PENDING);
        int sent = 0;
        for (int index = 0; index < pending.size(); index += RELAY_BATCH_SIZE) {
            List<SourceOutboxEntity> batch = pending.subList(index,
                    Math.min(index + RELAY_BATCH_SIZE, pending.size()));
            for (SourceOutboxEntity row : batch) {
                SourceInboxEntity inbox = inboxRepository.findById(row.getInboxId()).orElse(null);
                if (inbox == null) {
                    row.setStatus(SourceOutboxEntity.STATUS_FAILED);
                    row.setLastError("inbox row missing");
                    outboxRepository.save(row);
                    continue;
                }
                RawEnvelope envelope = new RawEnvelope(RawEnvelope.CONTRACT_VERSION,
                        UUID.fromString(row.getIngestionId()),
                        new DataEvent("github:" + inbox.getGithubEventId(), source, inbox.getEventType(),
                                inbox.getCreatedAt(), toMap(inbox.getPayload())),
                        inbox.getReceivedAt(),
                        RawEnvelope.Origin.GITHUB,
                        RawEnvelope.Mode.valueOf(inbox.getMode()),
                        inbox.getOriginReference(), null);
                try {
                    rawTemplate.send(KafkaTopics.RAW_EVENTS_V1,
                                    ScopeKey.of(source, inbox.getEventType()), envelope)
                            .get(10, TimeUnit.SECONDS);
                    row.setStatus(SourceOutboxEntity.STATUS_SENT);
                    row.setSentAt(Instant.now());
                    outboxRepository.save(row);
                    sent++;
                } catch (Exception e) {
                    row.setAttempts(row.getAttempts() + 1);
                    row.setLastError(e.getClass().getSimpleName());
                    outboxRepository.save(row);
                    log.warn("source outbox row {} could not be published: {}", row.getId(), e.toString());
                    return sent;
                }
            }
        }
        return sent;
    }

    /**
     * Promotes the candidate checkpoint only when every staged record has a processing receipt or
     * a terminal dead letter, so a crash cannot skip data by advancing the ETag early.
     */
    void promoteIfComplete(String source, CollectorStateEntity state, Instant now) {
        if (state.getPendingPollRunId() == null) {
            return;
        }
        List<SourceOutboxEntity> pending = outboxRepository.findBySourceAndStatusOrderByIdAsc(
                source, SourceOutboxEntity.STATUS_PENDING);
        if (!pending.isEmpty()) {
            return;
        }
        Long pendingRunId = state.getPendingPollRunId();
        List<SourceInboxEntity> staged = inboxRepository.findBySourceOrderByCreatedAtDescGithubEventIdDesc(source)
                .stream().filter(row -> pendingRunId.equals(row.getPollRunId())).toList();
        for (SourceInboxEntity row : staged) {
            boolean processed = receiptRepository.existsById(row.getIngestionId());
            boolean deadLettered = deadLetterRepository.findByDiagnosticId("ingestion:" + row.getIngestionId())
                    .isPresent();
            if (!processed && !deadLettered) {
                return;
            }
        }
        state.setEtagApplied(state.getEtagCandidate());
        state.setStatus(CollectorStateEntity.STATUS_READY);
        state.setPendingPollRunId(null);
        if (state.getObservedFrom() == null) {
            state.setObservedFrom(staged.stream().map(SourceInboxEntity::getCreatedAt)
                    .min(Comparator.naturalOrder()).orElse(null));
        }
        state.setLastEventAt(staged.stream().map(SourceInboxEntity::getCreatedAt)
                .max(Comparator.naturalOrder()).orElse(state.getLastEventAt()));
        metrics.recordCollectorUpstreamLag(state.getLastEventAt());
        state.setNextPollAt(now.plus(nextInterval(state, null)));
        state.setUpdatedAt(now);
        stateRepository.save(state);

        pollRunRepository.findById(pendingRunId).ifPresent(run -> {
            run.setStatus(SourcePollRunEntity.STATUS_APPLIED);
            pollRunRepository.save(run);
        });
    }

    // ------------------------------------------------------------------ outcomes

    private void finishQuiet(CollectorStateEntity state, SourcePollRunEntity run,
                             GithubFetchResult result, Instant now) {
        metrics.recordCollectorPoll("quiet");
        run.setStatus(SourcePollRunEntity.STATUS_QUIET);
        run.setEtagCandidate(state.getEtagApplied());
        run.setXPollInterval(result.pollIntervalSeconds());
        run.setFinishedAt(now);
        pollRunRepository.save(run);
        state.setStatus(CollectorStateEntity.STATUS_READY);
        state.setLastPollSuccess(now);
        state.setConsecutiveFailures(0);
        state.setNextPollAt(now.plus(nextInterval(state, result.pollIntervalSeconds())));
        state.setUpdatedAt(now);
        stateRepository.save(state);
        log.info("github poll for {} returned 304; next poll at {}", state.getSource(), state.getNextPollAt());
    }

    private void handleFailure(CollectorStateEntity state, SourcePollRunEntity run,
                               GithubFetchResult result, Instant now) {
        metrics.recordCollectorPoll("failed");
        metrics.recordCollectorFailure(result.status().name());
        run.setStatus(SourcePollRunEntity.STATUS_FAILED);
        run.setFailureReason(result.failureReason());
        run.setFinishedAt(now);
        pollRunRepository.save(run);
        switch (result.status()) {
            case RATE_LIMITED -> {
                Instant until = rateLimitBackoffUntil(state, result, now);
                applyBackoffUntil(state, until, "rate limited: " + result.failureReason(), now);
                recordGap(state.getSource(), run.getId(), "BUDGET_EXHAUSTED", null, null,
                        "rate limit backoff until " + until);
            }
            case UNAUTHORIZED, NOT_FOUND -> {
                state.setStatus(CollectorStateEntity.STATUS_ERROR);
                state.setLastError(result.failureReason());
                state.setNextPollAt(now.plus(nextInterval(state, null)));
                state.setUpdatedAt(now);
                stateRepository.save(state);
            }
            default -> applyBackoff(state, SERVER_BACKOFF_MIN, result.failureReason(), now);
        }
    }

    private void applyBackoff(CollectorStateEntity state, Duration base, String reason, Instant now) {
        int failures = state.getConsecutiveFailures() + 1;
        long seconds = Math.min((long) (base.getSeconds() * Math.pow(2, Math.max(0, failures - 1))),
                SERVER_BACKOFF_MAX.getSeconds());
        long jitter = (long) (Math.random() * 1000);
        applyBackoffUntil(state, now.plusSeconds(seconds).plusMillis(jitter), reason, now);
    }

    private void applyBackoffUntil(CollectorStateEntity state, Instant until, String reason, Instant now) {
        state.setStatus(CollectorStateEntity.STATUS_BACKOFF);
        state.setBackoffUntil(until);
        state.setBackoffSeconds((int) Duration.between(now, until).getSeconds());
        state.setConsecutiveFailures(state.getConsecutiveFailures() + 1);
        state.setLastError(reason);
        state.setNextPollAt(until);
        state.setUpdatedAt(now);
        stateRepository.save(state);
        log.warn("github poll for {} backing off until {} ({})", state.getSource(), until, reason);
    }

    private Instant rateLimitBackoffUntil(CollectorStateEntity state, GithubFetchResult result, Instant now) {
        if (result.retryAfterSeconds() != null) {
            return now.plusSeconds(Math.min(result.retryAfterSeconds(), RATE_LIMIT_BACKOFF_MAX.getSeconds()));
        }
        if (result.rateLimitReset() != null && result.rateLimitReset().isAfter(now)) {
            return result.rateLimitReset();
        }
        long seconds = Math.min(RATE_LIMIT_BACKOFF_MIN.getSeconds()
                        * (long) Math.pow(2, Math.max(0, state.getConsecutiveFailures())),
                RATE_LIMIT_BACKOFF_MAX.getSeconds());
        return now.plusSeconds(seconds);
    }

    // ------------------------------------------------------------------ gaps and helpers

    private void detectVisibilityGaps(CollectorStateEntity state, SourcePollRunEntity run,
                                      StageResult staged, Instant now) {
        String source = state.getSource();
        if (staged.oldestCreatedAt() == null) {
            return;
        }
        if (state.getObservedFrom() != null && staged.oldestCreatedAt().isAfter(state.getObservedFrom())) {
            recordGap(source, run.getId(), "NO_OVERLAP", staged.oldestCreatedAt(), staged.newestCreatedAt(),
                    "the visible window starts after the previously observed range");
        }
        Instant lastSuccess = state.getLastPollSuccess();
        if (lastSuccess != null
                && staged.oldestCreatedAt().isAfter(lastSuccess.plus(properties.source().github().pollInterval()))) {
            recordGap(source, run.getId(), "DOWNTIME_BEYOND_VISIBLE_RANGE",
                    staged.oldestCreatedAt(), staged.newestCreatedAt(),
                    "events created after the last successful poll are no longer visible");
        }
    }

    private void recordGap(String source, Long pollRunId, String reason, Instant visibleFrom,
                           Instant visibleTo, String detail) {
        SourceGapEntity gap = new SourceGapEntity();
        gap.setSource(source);
        gap.setPollRunId(pollRunId);
        gap.setReason(reason);
        gap.setVisibleFrom(visibleFrom);
        gap.setVisibleTo(visibleTo);
        gap.setMissingCount("unknown");
        gap.setRecoveryState(SourceGapEntity.STATE_OPEN);
        gap.setDetail(detail);
        gap.setCreatedAt(Instant.now());
        gapRepository.save(gap);
        log.warn("source gap recorded for {}: {} ({})", source, reason, detail);
    }

    private Duration nextInterval(CollectorStateEntity state, Integer xPollInterval) {
        Duration configured = properties.source().github().pollInterval();
        Duration interval = configured.compareTo(MIN_POLL_INTERVAL) >= 0 ? configured : MIN_POLL_INTERVAL;
        if (xPollInterval != null) {
            Duration upstream = Duration.ofSeconds(xPollInterval);
            if (upstream.compareTo(interval) > 0) {
                interval = upstream;
            }
        }
        return interval;
    }

    private CollectorStateEntity initialState(String source, Instant now) {
        CollectorStateEntity state = new CollectorStateEntity();
        state.setSource(source);
        state.setStatus(CollectorStateEntity.STATUS_READY);
        state.setNextPollAt(now);
        state.setUpdatedAt(now);
        return stateRepository.save(state);
    }

    private boolean acquireLease(CollectorStateEntity state, Instant now) {
        CollectorStateEntity current = stateRepository.findById(state.getSource()).orElseThrow();
        if (current.getLeaseExpiresAt() != null && current.getLeaseExpiresAt().isAfter(now)) {
            return false;
        }
        current.setLeaseOwner("driftwatch-" + ProcessHandle.current().pid());
        current.setLeaseExpiresAt(now.plus(LEASE_DURATION));
        current.setUpdatedAt(now);
        stateRepository.save(current);
        state.setLeaseOwner(current.getLeaseOwner());
        state.setLeaseExpiresAt(current.getLeaseExpiresAt());
        return true;
    }

    private void releaseLease(CollectorStateEntity state, Instant now) {
        stateRepository.findById(state.getSource()).ifPresent(current -> {
            current.setLeaseOwner(null);
            current.setLeaseExpiresAt(null);
            current.setUpdatedAt(now);
            stateRepository.save(current);
        });
    }

    private boolean allKnown(String source, List<JsonNode> page) {
        if (page.isEmpty()) {
            return true;
        }
        return page.stream().allMatch(raw -> {
            String id = raw.path("id").asText(null);
            return id != null && inboxRepository.existsBySourceAndGithubEventId(source, id);
        });
    }

    private List<JsonNode> toList(JsonNode array) {
        List<JsonNode> list = new ArrayList<>();
        array.forEach(list::add);
        return list;
    }

    private List<JsonNode> recordsOfLastPage(List<JsonNode> records, int pageSize, int pagesRead) {
        int from = (pagesRead - 1) * pageSize;
        if (from >= records.size()) {
            return List.of();
        }
        return records.subList(from, Math.min(from + pageSize, records.size()));
    }

    private java.util.Map<String, Object> toMap(JsonNode payload) {
        return new com.fasterxml.jackson.databind.ObjectMapper()
                .convertValue(payload, new com.fasterxml.jackson.core.type.TypeReference<
                        java.util.LinkedHashMap<String, Object>>() {});
    }
}
