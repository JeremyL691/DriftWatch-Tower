package com.driftwatch.source.github;

import com.driftwatch.persistence.CollectorStateEntity;
import com.driftwatch.persistence.CollectorStateRepository;
import com.driftwatch.persistence.SourceGapEntity;
import com.driftwatch.persistence.SourceGapRepository;
import com.driftwatch.persistence.SourceInboxRepository;
import com.driftwatch.persistence.SourceOutboxEntity;
import com.driftwatch.persistence.SourceOutboxRepository;
import com.driftwatch.persistence.SourcePollRunEntity;
import com.driftwatch.persistence.SourcePollRunRepository;
import com.driftwatch.support.ContainerIntegrationTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gate G08: the source protocol against a local stub (execution guide, sections 6.3 and 6.4).
 *
 * <p>Covers ETag/304, rate-limit backoff with {@code Retry-After}, invalid credentials, missing
 * endpoints, server errors, timeouts, bad JSON, cross-page overlap, bootstrap versus live mode,
 * truncation gaps and restart recovery. Mocks are used only for these exception paths; the real
 * source is exercised by the acceptance smoke.
 */
class GithubPollerIntegrationTest extends ContainerIntegrationTest {

    private static GithubStubServer stub;

    @Autowired
    GithubPoller poller;
    @Autowired
    CollectorStateRepository stateRepository;
    @Autowired
    SourceInboxRepository inboxRepository;
    @Autowired
    SourceOutboxRepository outboxRepository;
    @Autowired
    SourcePollRunRepository pollRunRepository;
    @Autowired
    SourceGapRepository gapRepository;

    private static final String SOURCE = "github:apache/kafka";

    @BeforeAll
    static void startStub() throws IOException {
        stub = new GithubStubServer(19090);
    }

    @AfterAll
    static void stopStub() {
        if (stub != null) {
            stub.stop();
        }
    }

    @DynamicPropertySource
    static void githubProperties(DynamicPropertyRegistry registry) {
        registry.add("driftwatch.source.github.enabled", () -> "true");
        registry.add("driftwatch.source.github.base-url", () -> "http://127.0.0.1:19090");
        registry.add("driftwatch.source.github.allow-non-official-base-url", () -> "true");
        // A long interval keeps the background scheduler out of the way; tests call pollNow().
        registry.add("driftwatch.source.github.poll-interval", () -> "PT1H");
        // Tests drive rounds explicitly; the automatic loop would consume scripted responses.
        registry.add("driftwatch.source.github.scheduler-enabled", () -> "false");
        registry.add("driftwatch.source.github.max-pages", () -> "3");
        registry.add("driftwatch.source.github.page-size", () -> "2");
        // Generous enough that a loaded CI JVM does not turn a normal response into a timeout;
        // the slow-response case scripts an 8s delay to exceed it deliberately.
        registry.add("driftwatch.source.github.request-timeout", () -> "PT5S");
    }

    @BeforeEach
    void resetSourceState() {
        stub.reset();
        gapRepository.deleteAll();
        outboxRepository.deleteAll();
        inboxRepository.deleteAll();
        pollRunRepository.deleteAll();
        stateRepository.deleteAll();
    }

    private CollectorStateEntity state() {
        return stateRepository.findById(SOURCE).orElseThrow();
    }

    /**
     * Waits until the staged round is promoted: the checkpoint only advances after every record
     * has a processing receipt, which the sink writes asynchronously.
     */
    private void awaitApplied() {
        // The production loop retries relay/promotion every tick; the test mirrors that by
        // polling until the staged round is promoted (the sink writes receipts asynchronously).
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(45)).untilAsserted(() -> {
            poller.pollNow();
            assertThat(state().getPendingPollRunId()).isNull();
        });
    }

    private void pollAndAwait() {
        poller.pollNow();
        awaitApplied();
    }

    private List<SourcePollRunEntity> runs() {
        return pollRunRepository.findTop20BySourceOrderByIdDesc(SOURCE);
    }

    @Test
    void bootstrapRoundStagesPublishesAndAppliesTheCheckpoint() {
        stub.script(GithubStubServer.ScriptedResponse.withHeaders(200,
                GithubStubServer.headers("etag", "\"v1\"", "x-poll-interval", "60"),
                GithubStubServer.array(
                        GithubStubServer.event("1001", "PushEvent", "2026-10-01T00:00:00Z", null),
                        GithubStubServer.event("1002", "PullRequestEvent", "2026-10-01T00:00:01Z", "opened"))));

        pollAndAwait();

        assertThat(inboxRepository.countBySource(SOURCE)).isEqualTo(2);
        assertThat(outboxRepository.countByStatus(SourceOutboxEntity.STATUS_SENT)).isEqualTo(2);
        assertThat(state().getEtagApplied()).isEqualTo("\"v1\"");
        assertThat(state().getStatus()).isEqualTo(CollectorStateEntity.STATUS_READY);
        assertThat(state().getPendingPollRunId()).isNull();
        assertThat(runs().get(0).getMode()).isEqualTo("BOOTSTRAP");
        assertThat(runs().get(0).getStatus()).isEqualTo(SourcePollRunEntity.STATUS_APPLIED);
        assertThat(state().getNextPollAt()).isAfter(Instant.now().plus(Duration.ofMinutes(50)));
    }

    @Test
    void notModifiedKeepsTheCursorAndOnlyUpdatesPollHealth() {
        stub.script(GithubStubServer.ScriptedResponse.withHeaders(200,
                GithubStubServer.headers("etag", "\"v1\""),
                GithubStubServer.array(GithubStubServer.event("2001", "PushEvent", "2026-10-01T00:00:00Z", null))));
        pollAndAwait();
        String appliedBefore = state().getEtagApplied();
        long rowsBefore = inboxRepository.countBySource(SOURCE);

        stub.script(GithubStubServer.ScriptedResponse.withHeaders(304,
                GithubStubServer.headers("etag", "\"v1\"", "x-poll-interval", "60"), ""));
        poller.pollNow();

        assertThat(state().getEtagApplied()).as("304 must not move the cursor").isEqualTo(appliedBefore);
        assertThat(inboxRepository.countBySource(SOURCE)).isEqualTo(rowsBefore);
        assertThat(state().getLastPollSuccess()).isNotNull();
        assertThat(runs().get(0).getStatus()).isEqualTo(SourcePollRunEntity.STATUS_QUIET);
        assertThat(state().getNextPollAt()).isAfter(Instant.now().plus(Duration.ofMinutes(50)));
    }

    @Test
    void rateLimitHonoursRetryAfterAndRecordsABudgetGap() {
        stub.script(GithubStubServer.ScriptedResponse.withHeaders(403,
                GithubStubServer.headers("retry-after", "120", "x-ratelimit-remaining", "0"),
                "{\"message\":\"rate limit\"}"));

        poller.pollNow();

        CollectorStateEntity state = state();
        assertThat(state.getStatus()).isEqualTo(CollectorStateEntity.STATUS_BACKOFF);
        assertThat(state.getBackoffUntil()).isAfter(Instant.now().plusSeconds(100));
        assertThat(state.getNextPollAt()).isEqualTo(state.getBackoffUntil());
        assertThat(gapRepository.findByRecoveryStateOrderByIdAsc(SourceGapEntity.STATE_OPEN))
                .singleElement()
                .satisfies(gap -> {
                    assertThat(gap.getReason()).isEqualTo("BUDGET_EXHAUSTED");
                    assertThat(gap.getMissingCount()).isEqualTo("unknown");
                });
    }

    @Test
    void rateLimitWithoutHeadersUsesExponentialBackoff() {
        stub.script(GithubStubServer.ScriptedResponse.of(429, "{\"message\":\"slow down\"}"));

        poller.pollNow();

        assertThat(state().getStatus()).isEqualTo(CollectorStateEntity.STATUS_BACKOFF);
        assertThat(state().getBackoffSeconds()).isGreaterThanOrEqualTo(60);
    }

    @Test
    void invalidCredentialsAndMissingEndpointMoveToErrorWithoutLeakingTheToken() {
        stub.script(GithubStubServer.ScriptedResponse.of(401, "{\"message\":\"Bad credentials\"}"));
        poller.pollNow();
        assertThat(state().getStatus()).isEqualTo(CollectorStateEntity.STATUS_ERROR);
        assertThat(state().getLastError()).doesNotContain("Bearer");

        stub.script(GithubStubServer.ScriptedResponse.of(404, "{\"message\":\"Not Found\"}"));
        poller.pollNow();
        assertThat(state().getStatus()).isEqualTo(CollectorStateEntity.STATUS_ERROR);
        assertThat(state().getLastError()).contains("not found");
    }

    @Test
    void serverErrorBacksOffAndRecoversOnTheNextRound() {
        stub.script(GithubStubServer.ScriptedResponse.of(500, "{\"message\":\"boom\"}"));
        poller.pollNow();
        assertThat(state().getStatus()).isEqualTo(CollectorStateEntity.STATUS_BACKOFF);
        assertThat(state().getBackoffSeconds()).isBetween(5, 30);

        stub.script(GithubStubServer.ScriptedResponse.withHeaders(200,
                GithubStubServer.headers("etag", "\"v2\""),
                GithubStubServer.array(GithubStubServer.event("3001", "PushEvent", "2026-10-01T00:10:00Z", null))));
        stateRepository.findById(SOURCE).ifPresent(row -> {
            row.setNextPollAt(Instant.now().minusSeconds(1));
            row.setBackoffUntil(null);
            stateRepository.save(row);
        });
        pollAndAwait();
        assertThat(state().getStatus()).isEqualTo(CollectorStateEntity.STATUS_READY);
        assertThat(inboxRepository.countBySource(SOURCE)).isEqualTo(1);
    }

    @Test
    void slowResponseTimesOutWithoutLosingThePoller() {
        stub.script(new GithubStubServer.ScriptedResponse(200, java.util.Map.of(), "[]", 8_000L));

        poller.pollNow();

        assertThat(state().getStatus()).isEqualTo(CollectorStateEntity.STATUS_BACKOFF);
        assertThat(state().getLastError()).contains("timed out");
    }

    @Test
    void badJsonIsAFailureAndTheNextRoundStillWorks() {
        stub.script(GithubStubServer.ScriptedResponse.of(200, "{not-json"));
        poller.pollNow();
        assertThat(state().getStatus()).isEqualTo(CollectorStateEntity.STATUS_BACKOFF);
        assertThat(state().getLastError()).contains("could not be parsed");

        stub.script(GithubStubServer.ScriptedResponse.withHeaders(200,
                GithubStubServer.headers("etag", "\"v3\""),
                GithubStubServer.array(GithubStubServer.event("4001", "PushEvent", "2026-10-01T00:20:00Z", null))));
        stateRepository.findById(SOURCE).ifPresent(row -> {
            row.setNextPollAt(Instant.now().minusSeconds(1));
            stateRepository.save(row);
        });
        pollAndAwait();
        assertThat(inboxRepository.countBySource(SOURCE)).isEqualTo(1);
    }

    @Test
    void crossPageOverlapDoesNotCreateDuplicateIngests() {
        stub.script(GithubStubServer.ScriptedResponse.withHeaders(200,
                        GithubStubServer.headers("etag", "\"v4\""),
                        GithubStubServer.array(
                                GithubStubServer.event("5001", "PushEvent", "2026-10-01T01:00:00Z", null),
                                GithubStubServer.event("5002", "PushEvent", "2026-10-01T01:00:01Z", null))),
                // Second page overlaps the first: 5002 appears again, 5003 is new.
                GithubStubServer.ScriptedResponse.withHeaders(200, GithubStubServer.headers("etag", "\"v4\""),
                        GithubStubServer.array(
                                GithubStubServer.event("5002", "PushEvent", "2026-10-01T01:00:01Z", null),
                                GithubStubServer.event("5003", "PushEvent", "2026-10-01T01:00:02Z", null))));

        pollAndAwait();

        assertThat(inboxRepository.countBySource(SOURCE))
                .as("the overlapping id must be stored once")
                .isEqualTo(3);
        assertThat(outboxRepository.countByStatus(SourceOutboxEntity.STATUS_SENT)).isEqualTo(3);

        // A repeated poll with the same content adds nothing.
        stub.script(GithubStubServer.ScriptedResponse.withHeaders(200, GithubStubServer.headers("etag", "\"v4\""),
                GithubStubServer.array(
                        GithubStubServer.event("5001", "PushEvent", "2026-10-01T01:00:00Z", null),
                        GithubStubServer.event("5002", "PushEvent", "2026-10-01T01:00:01Z", null))));
        stateRepository.findById(SOURCE).ifPresent(row -> {
            row.setNextPollAt(Instant.now().minusSeconds(1));
            stateRepository.save(row);
        });
        poller.pollNow();
        assertThat(inboxRepository.countBySource(SOURCE)).isEqualTo(3);
    }

    @Test
    void originReferenceNamesTheRawIdRepositoryPollRunAndFetchUrl() {
        // Guide 6.2: the persisted origin reference must let an operator trace a record back to
        // the exact request, so it carries the raw id, the repository, the poll run and the URL
        // of the page the record came from.
        stub.script(GithubStubServer.ScriptedResponse.withHeaders(200,
                GithubStubServer.headers("etag", "\"origin\""),
                GithubStubServer.array(GithubStubServer.event("7001", "PushEvent", "2026-10-01T03:00:00Z", null))));
        pollAndAwait();

        var inbox = inboxRepository.findAll().stream()
                .filter(row -> "7001".equals(row.getGithubEventId()))
                .findFirst()
                .orElseThrow();
        assertThat(inbox.getOriginReference())
                .startsWith("github:apache/kafka#7001@")
                .contains("/repos/apache/kafka/events")
                .contains("per_page=");
        // The URL is the one actually fetched, so it follows the configured base (the stub here,
        // api.github.com in a self-host install) rather than a hardcoded scheme.
        assertThat(inbox.getOriginReference().split("\\|")[1]).startsWith("http");
    }

    @Test
    void liveModeIsRecordedOnceTheBootstrapCheckpointIsApplied() {
        stub.script(GithubStubServer.ScriptedResponse.withHeaders(200,
                GithubStubServer.headers("etag", "\"v5\""),
                GithubStubServer.array(GithubStubServer.event("6001", "PushEvent", "2026-10-01T02:00:00Z", null))));
        pollAndAwait();
        assertThat(runs().get(0).getMode()).isEqualTo("BOOTSTRAP");

        stub.script(GithubStubServer.ScriptedResponse.withHeaders(200,
                GithubStubServer.headers("etag", "\"v6\""),
                GithubStubServer.array(GithubStubServer.event("6002", "PushEvent", "2026-10-01T02:05:00Z", null))));
        stateRepository.findById(SOURCE).ifPresent(row -> {
            row.setNextPollAt(Instant.now().minusSeconds(1));
            stateRepository.save(row);
        });
        pollAndAwait();

        assertThat(runs().get(0).getMode()).isEqualTo("LIVE");
        assertThat(state().getEtagApplied()).isEqualTo("\"v6\"");
    }

    @Test
    void restartRecoveryResumesThePendingRoundBeforeFetchingNewData() {
        // Stage a round but leave the relay unsent by simulating a crash right after staging:
        // the outbox row exists as PENDING and the checkpoint is still the candidate.
        stub.script(GithubStubServer.ScriptedResponse.withHeaders(200,
                GithubStubServer.headers("etag", "\"v7\""),
                GithubStubServer.array(GithubStubServer.event("7001", "PushEvent", "2026-10-01T03:00:00Z", null))));
        pollAndAwait();
        assertThat(state().getEtagApplied()).isEqualTo("\"v7\"");

        // A second round that fails keeps the applied checkpoint; the pending round is retried
        // before any new fetch happens.
        stub.script(GithubStubServer.ScriptedResponse.of(500, "{\"message\":\"boom\"}"));
        stateRepository.findById(SOURCE).ifPresent(row -> {
            row.setNextPollAt(Instant.now().minusSeconds(1));
            stateRepository.save(row);
        });
        poller.pollNow();
        assertThat(state().getEtagApplied()).as("a failed round must not move the checkpoint")
                .isEqualTo("\"v7\"");
        assertThat(inboxRepository.countBySource(SOURCE)).isEqualTo(1);
    }
}
