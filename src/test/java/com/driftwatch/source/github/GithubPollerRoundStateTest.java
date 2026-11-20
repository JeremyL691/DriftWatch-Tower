package com.driftwatch.source.github;

import com.driftwatch.config.DriftwatchProperties;
import com.driftwatch.operations.DriftwatchMetrics;
import com.driftwatch.persistence.CollectorStateEntity;
import com.driftwatch.persistence.CollectorStateRepository;
import com.driftwatch.persistence.DeadLetterRecordRepository;
import com.driftwatch.persistence.ProcessedReceiptRepository;
import com.driftwatch.persistence.SourceGapEntity;
import com.driftwatch.persistence.SourceGapRepository;
import com.driftwatch.persistence.SourceInboxEntity;
import com.driftwatch.persistence.SourceInboxRepository;
import com.driftwatch.persistence.SourceOutboxEntity;
import com.driftwatch.persistence.SourceOutboxRepository;
import com.driftwatch.persistence.SourcePollRunEntity;
import com.driftwatch.persistence.SourcePollRunRepository;
import com.driftwatch.source.CollectorStatusFixtures;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression for the poller's round state handling (guide 6.3 steps 5 and 6). {@code stage()} commits the
 * pending run through its own row, so the round must continue from the stored row. Otherwise promotion is
 * skipped until a later tick, and a failure after staging writes the pre-stage object back over the pending
 * run and candidate ETag.
 *
 * <p>Repositories are in-memory stand-ins that copy on read and on write, as JPA does, so a stale object
 * behaves as it would against the database.
 */
class GithubPollerRoundStateTest {

    private static final String SOURCE = "github:apache/kafka";

    private final Map<String, CollectorStateEntity> states = new HashMap<>();
    private final List<SourceInboxEntity> inboxRows = new ArrayList<>();
    private final Map<Long, SourcePollRunEntity> pollRuns = new HashMap<>();
    private final List<SourceGapEntity> gaps = new ArrayList<>();
    private final AtomicLong runIds = new AtomicLong(100);

    private final GithubEventsClient client = mock(GithubEventsClient.class);
    private final CollectorStateRepository stateRepository = mock(CollectorStateRepository.class);
    private final SourceInboxRepository inboxRepository = mock(SourceInboxRepository.class);
    private final SourceOutboxRepository outboxRepository = mock(SourceOutboxRepository.class);
    private final SourcePollRunRepository pollRunRepository = mock(SourcePollRunRepository.class);
    private final SourceGapRepository gapRepository = mock(SourceGapRepository.class);
    private final ProcessedReceiptRepository receiptRepository = mock(ProcessedReceiptRepository.class);
    private final DeadLetterRecordRepository deadLetterRepository = mock(DeadLetterRecordRepository.class);

    private final Instant now = Instant.now();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void wireRepositories() throws Exception {
        when(stateRepository.findById(anyString())).thenAnswer(inv ->
                Optional.ofNullable(states.get(inv.<String>getArgument(0))).map(GithubPollerRoundStateTest::copy));
        when(stateRepository.save(any(CollectorStateEntity.class))).thenAnswer(inv -> {
            CollectorStateEntity row = inv.getArgument(0);
            states.put(row.getSource(), copy(row));
            return row;
        });

        when(inboxRepository.save(any(SourceInboxEntity.class))).thenAnswer(inv -> {
            inboxRows.add(inv.getArgument(0));
            return inv.getArgument(0);
        });
        when(inboxRepository.existsBySourceAndGithubEventId(anyString(), anyString())).thenAnswer(inv -> {
            String eventId = inv.getArgument(1);
            return inboxRows.stream().anyMatch(row -> eventId.equals(row.getGithubEventId()));
        });
        when(inboxRepository.countBySource(anyString())).thenAnswer(inv -> (long) inboxRows.size());
        when(inboxRepository.findBySourceOrderByCreatedAtDescGithubEventIdDesc(anyString()))
                .thenAnswer(inv -> List.copyOf(inboxRows));

        // Every staged row is treated as already relayed: this test is about the round's state, not the relay.
        when(outboxRepository.save(any(SourceOutboxEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(outboxRepository.findBySourceAndStatusOrderByIdAsc(anyString(), anyString())).thenReturn(List.of());

        when(pollRunRepository.save(any(SourcePollRunEntity.class))).thenAnswer(inv -> {
            SourcePollRunEntity run = inv.getArgument(0);
            if (run.getId() == null) {
                assignId(run, runIds.incrementAndGet());
            }
            pollRuns.put(run.getId(), run);
            return run;
        });
        when(pollRunRepository.findById(anyLong()))
                .thenAnswer(inv -> Optional.ofNullable(pollRuns.get(inv.<Long>getArgument(0))));

        when(gapRepository.save(any(SourceGapEntity.class))).thenAnswer(inv -> {
            gaps.add(inv.getArgument(0));
            return inv.getArgument(0);
        });
        when(receiptRepository.existsById(anyString())).thenReturn(true);

        // The last applied checkpoint is e0, from an hour ago; the upstream page holds one newer event.
        CollectorStateEntity applied = new CollectorStateEntity();
        applied.setSource(SOURCE);
        applied.setStatus(CollectorStateEntity.STATUS_READY);
        applied.setEtagApplied("\"e0\"");
        applied.setLastPollSuccess(now.minus(Duration.ofHours(1)));
        applied.setLastEventAt(now.minus(Duration.ofHours(1)));
        applied.setObservedFrom(now.minus(Duration.ofHours(2)));
        applied.setNextPollAt(now.minus(Duration.ofMinutes(1)));
        applied.setUpdatedAt(now);
        states.put(SOURCE, copy(applied));

        JsonNode body = new ObjectMapper().readTree(GithubStubServer.array(
                GithubStubServer.event("7001", "PushEvent", now.minusSeconds(120).toString(), null)));
        when(client.fetchPage(anyString(), any())).thenReturn(new GithubFetchResult(
                GithubFetchResult.Status.OK, "\"e1\"", 60, null, null, 100, null, body, null));
    }

    private GithubPoller poller() {
        DriftwatchProperties properties = CollectorStatusFixtures.properties(Duration.ofHours(1));
        return new GithubPoller(properties, client, new GithubEventConverter(new ObjectMapper()),
                stateRepository, inboxRepository, outboxRepository, pollRunRepository, gapRepository,
                mock(KafkaTemplate.class), receiptRepository, deadLetterRepository,
                mock(PlatformTransactionManager.class), mock(DriftwatchMetrics.class));
    }

    @Test
    void roundPromotesItsOwnStagedCheckpointInTheRoundThatStagedIt() {
        poller().pollNow();

        CollectorStateEntity after = states.get(SOURCE);
        assertThat(after.getPendingPollRunId())
                .as("the round that staged the run must promote it, not leave it for a later tick")
                .isNull();
        assertThat(after.getEtagApplied()).isEqualTo("\"e1\"");
        assertThat(pollRuns.values()).extracting(SourcePollRunEntity::getStatus)
                .containsExactly(SourcePollRunEntity.STATUS_APPLIED);
    }

    @Test
    void failureAfterStagingBacksOffFromTheStoredRowAndKeepsThePendingRun() {
        // The visibility check runs after staging; failing there is a failure after the pending run exists.
        when(gapRepository.save(any(SourceGapEntity.class)))
                .thenThrow(new IllegalStateException("gap store unavailable"));

        poller().tick();

        CollectorStateEntity after = states.get(SOURCE);
        assertThat(after.getStatus()).isEqualTo(CollectorStateEntity.STATUS_BACKOFF);
        assertThat(after.getPendingPollRunId())
                .as("the backoff save must not erase the staged run")
                .isNotNull();
        assertThat(after.getEtagCandidate()).isEqualTo("\"e1\"");
        assertThat(after.getEtagApplied()).as("the checkpoint is not applied by a failed round").isEqualTo("\"e0\"");
    }

    @Test
    void roundFollowsUpstreamPollIntervalWhenLargerThanConfigured() throws Exception {
        when(client.fetchPage(anyString(), any())).thenReturn(new GithubFetchResult(
                GithubFetchResult.Status.OK, "\"e2\"", 900, null, null, 100, null,
                new ObjectMapper().readTree(GithubStubServer.array(
                        GithubStubServer.event("7002", "PushEvent", now.minusSeconds(30).toString(), null))),
                null));

        DriftwatchProperties properties = CollectorStatusFixtures.properties(Duration.ofMinutes(5));
        GithubPoller customPoller = new GithubPoller(properties, client, new GithubEventConverter(new ObjectMapper()),
                stateRepository, inboxRepository, outboxRepository, pollRunRepository, gapRepository,
                mock(KafkaTemplate.class), receiptRepository, deadLetterRepository,
                mock(PlatformTransactionManager.class), mock(DriftwatchMetrics.class));

        Instant before = Instant.now();
        customPoller.pollNow();
        CollectorStateEntity after = states.get(SOURCE);

        assertThat(after.getNextPollAt())
                .isAfterOrEqualTo(before.plus(Duration.ofSeconds(900)))
                .isBeforeOrEqualTo(Instant.now().plus(Duration.ofSeconds(900)));
    }

    private static void assignId(SourcePollRunEntity run, long id) {
        try {
            Field field = SourcePollRunEntity.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(run, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static CollectorStateEntity copy(CollectorStateEntity from) {
        CollectorStateEntity to = new CollectorStateEntity();
        to.setSource(from.getSource());
        to.setStatus(from.getStatus());
        to.setEtagApplied(from.getEtagApplied());
        to.setEtagCandidate(from.getEtagCandidate());
        to.setLastPollAt(from.getLastPollAt());
        to.setLastPollSuccess(from.getLastPollSuccess());
        to.setLastEventAt(from.getLastEventAt());
        to.setNextPollAt(from.getNextPollAt());
        to.setBackoffUntil(from.getBackoffUntil());
        to.setBackoffSeconds(from.getBackoffSeconds());
        to.setConsecutiveFailures(from.getConsecutiveFailures());
        to.setLastError(from.getLastError());
        to.setLeaseOwner(from.getLeaseOwner());
        to.setLeaseExpiresAt(from.getLeaseExpiresAt());
        to.setPendingPollRunId(from.getPendingPollRunId());
        to.setObservedFrom(from.getObservedFrom());
        to.setUpdatedAt(from.getUpdatedAt());
        return to;
    }
}
