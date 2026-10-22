package com.driftwatch.source;

import com.driftwatch.config.DriftwatchProperties;
import com.driftwatch.persistence.CollectorStateEntity;
import com.driftwatch.persistence.CollectorStateRepository;
import com.driftwatch.persistence.SourceGapRepository;
import com.driftwatch.persistence.SourceOutboxEntity;
import com.driftwatch.persistence.SourceOutboxRepository;
import com.driftwatch.persistence.SourcePollRunEntity;
import com.driftwatch.persistence.SourcePollRunRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Collector status for the operations API (execution guide, sections 4.5 and 7.2).
 *
 * <p>The collector is "lost" only when {@code last_poll_success} is older than
 * {@code max(15 minutes, 2x the current legal poll interval)}; a successful poll without new
 * events is QUIET, not lost, and an upstream backoff is reported as its own state. RUNNING means
 * the most recent finished poll delivered new records; a 304 or a round without new records is
 * QUIET. Reads are pure.
 */
@Service
public class CollectorStatusService {

    static final Duration MIN_LOST_AFTER = Duration.ofMinutes(15);

    private final CollectorStateRepository stateRepository;
    private final SourceOutboxRepository outboxRepository;
    private final SourceGapRepository gapRepository;
    private final SourcePollRunRepository pollRunRepository;
    private final DriftwatchProperties properties;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public CollectorStatusService(CollectorStateRepository stateRepository,
                                  SourceOutboxRepository outboxRepository,
                                  SourceGapRepository gapRepository,
                                  SourcePollRunRepository pollRunRepository,
                                  com.driftwatch.config.RuntimeConfiguration runtime,
                                  Clock clock) {
        this(stateRepository, outboxRepository, gapRepository, pollRunRepository, runtime.startupProperties(), clock);
    }

    public CollectorStatusService(CollectorStateRepository stateRepository,
                                  SourceOutboxRepository outboxRepository,
                                  SourceGapRepository gapRepository,
                                  SourcePollRunRepository pollRunRepository,
                                  DriftwatchProperties properties,
                                  Clock clock) {
        this.stateRepository = stateRepository;
        this.outboxRepository = outboxRepository;
        this.gapRepository = gapRepository;
        this.pollRunRepository = pollRunRepository;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> collectors() {
        String configuredSource = properties.source().github().sourceId();
        return stateRepository.findAll().stream()
                .filter(state -> state.getSource().equals(configuredSource))
                .map(this::view)
                .toList();
    }

    /**
     * The silence a GitHub collector may show before it is LOST: {@code max(15 minutes, 2x the
     * current legal poll interval)}. Source health reuses this exact threshold (guide 7.2).
     */
    Duration lostAfter() {
        Duration legalInterval = properties.source().github().pollInterval();
        Duration configuredLost = properties.sourceHealth() != null && properties.sourceHealth().collectorLostAfter() != null
                ? properties.sourceHealth().collectorLostAfter()
                : MIN_LOST_AFTER;
        Duration threshold = configuredLost.compareTo(MIN_LOST_AFTER) > 0 ? configuredLost : MIN_LOST_AFTER;
        return legalInterval.multipliedBy(2).compareTo(threshold) > 0
                ? legalInterval.multipliedBy(2) : threshold;
    }

    /** Lost means the last successful poll is older than {@link #lostAfter()} at {@code now}. */
    boolean isLost(CollectorStateEntity state, Instant now) {
        return state.getLastPollSuccess() != null
                && state.getLastPollSuccess().isBefore(now.minus(lostAfter()));
    }

    Map<String, Object> view(CollectorStateEntity state) {
        Instant now = clock.instant();
        Duration lostAfter = lostAfter();
        boolean lost = isLost(state, now);

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("source", state.getSource());
        view.put("status", status(state, lost, lastFinishedRun(state.getSource())));
        view.put("last_poll_at", state.getLastPollAt());
        view.put("last_success_at", state.getLastPollSuccess());
        view.put("next_poll_at", state.getNextPollAt());
        view.put("last_event_at", state.getLastEventAt());
        view.put("upstream_lag_seconds", state.getLastEventAt() == null ? null
                : Duration.between(state.getLastEventAt(), now).getSeconds());
        view.put("lost", lost);
        view.put("lost_after_seconds", lostAfter.getSeconds());
        view.put("backoff_until", state.getBackoffUntil());
        view.put("etag_applied", state.getEtagApplied() != null);
        view.put("etag_candidate", state.getEtagCandidate() != null);
        view.put("pending_poll_run_id", state.getPendingPollRunId());
        view.put("observed_from", state.getObservedFrom());
        view.put("updated_at", state.getUpdatedAt());
        view.put("collector_enabled", properties.source().github().enabled());
        view.put("poll_interval_ms", properties.source().github().pollInterval().toMillis());
        view.put("pending_outbox", outboxRepository.countBySourceAndStatus(state.getSource(), SourceOutboxEntity.STATUS_PENDING));
        view.put("open_gaps", gapRepository.countBySourceAndRecoveryState(state.getSource(),
                com.driftwatch.persistence.SourceGapEntity.STATE_OPEN));
        view.put("recent_gaps", gapRepository.findTop20BySourceOrderByIdDesc(state.getSource()).stream()
                .map(this::gapView).toList());
        view.put("last_error", state.getLastError());
        return view;
    }

    private Map<String, Object> gapView(com.driftwatch.persistence.SourceGapEntity gap) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", gap.getId()); view.put("poll_run_id", gap.getPollRunId());
        view.put("reason", gap.getReason()); view.put("visible_from", gap.getVisibleFrom());
        view.put("visible_to", gap.getVisibleTo()); view.put("confirmed_from", gap.getConfirmedFrom());
        view.put("missing_count", gap.getMissingCount()); view.put("recovery_state", gap.getRecoveryState());
        view.put("created_at", gap.getCreatedAt()); view.put("recovered_at", gap.getRecoveredAt());

        boolean heuristic = "NO_OVERLAP".equalsIgnoreCase(gap.getReason())
                || "DOWNTIME_BEYOND_VISIBLE_RANGE".equalsIgnoreCase(gap.getReason())
                || "unknown".equalsIgnoreCase(gap.getMissingCount());
        view.put("evidence_level", heuristic ? "SUSPECTED" : "RECORDED");
        view.put("evidence_note", heuristic
                ? "Discontinuity detected by poll timestamps; unconfirmed record loss"
                : "Collector-recorded gap; missing record count is reported as stored");
        return view;
    }

    /** The newest poll run that has finished; a round still in progress does not count. */
    private Optional<SourcePollRunEntity> lastFinishedRun(String source) {
        return pollRunRepository.findTop20BySourceOrderByIdDesc(source).stream()
                .filter(run -> !SourcePollRunEntity.STATUS_RUNNING.equals(run.getStatus()))
                .findFirst();
    }

    private String status(CollectorStateEntity state, boolean lost, Optional<SourcePollRunEntity> lastFinished) {
        if (lost) {
            return "LOST";
        }
        if (CollectorStateEntity.STATUS_BACKOFF.equals(state.getStatus())) {
            return "BACKOFF";
        }
        if (CollectorStateEntity.STATUS_ERROR.equals(state.getStatus())) {
            return "ERROR";
        }
        // Only a finished round that staged or applied new records is RUNNING. A 304 or a round
        // that found nothing new is QUIET, not lost (guide 6.3 and 7.2). The upstream created_at of
        // the newest event is always older than the wall-clock poll time, so it cannot tell these apart.
        boolean deliveredRecords = lastFinished
                .filter(run -> SourcePollRunEntity.STATUS_STAGED.equals(run.getStatus())
                        || SourcePollRunEntity.STATUS_APPLIED.equals(run.getStatus()))
                .map(run -> run.getNewRecords() > 0)
                .orElse(false);
        return deliveredRecords ? "RUNNING" : "QUIET";
    }
}
