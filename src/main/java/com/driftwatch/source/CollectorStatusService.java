package com.driftwatch.source;

import com.driftwatch.config.DriftwatchProperties;
import com.driftwatch.persistence.CollectorStateEntity;
import com.driftwatch.persistence.CollectorStateRepository;
import com.driftwatch.persistence.SourceGapRepository;
import com.driftwatch.persistence.SourceOutboxEntity;
import com.driftwatch.persistence.SourceOutboxRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Collector status for the operations API (execution guide, sections 4.5 and 7.2).
 *
 * <p>The collector is "lost" only when {@code last_poll_success} is older than
 * {@code max(15 minutes, 2x the current legal poll interval)}; a successful poll without new
 * events is QUIET, not lost, and an upstream backoff is reported as its own state. Reads are
 * pure.
 */
@Service
public class CollectorStatusService {

    static final Duration MIN_LOST_AFTER = Duration.ofMinutes(15);

    private final CollectorStateRepository stateRepository;
    private final SourceOutboxRepository outboxRepository;
    private final SourceGapRepository gapRepository;
    private final DriftwatchProperties properties;
    private final Clock clock;

    public CollectorStatusService(CollectorStateRepository stateRepository,
                                  SourceOutboxRepository outboxRepository,
                                  SourceGapRepository gapRepository,
                                  DriftwatchProperties properties,
                                  Clock clock) {
        this.stateRepository = stateRepository;
        this.outboxRepository = outboxRepository;
        this.gapRepository = gapRepository;
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

    Map<String, Object> view(CollectorStateEntity state) {
        Instant now = clock.instant();
        Duration legalInterval = properties.source().github().pollInterval();
        Duration lostAfter = legalInterval.multipliedBy(2).compareTo(MIN_LOST_AFTER) > 0
                ? legalInterval.multipliedBy(2) : MIN_LOST_AFTER;
        boolean lost = state.getLastPollSuccess() != null
                && state.getLastPollSuccess().isBefore(now.minus(lostAfter));

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("source", state.getSource());
        view.put("status", status(state, lost));
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
        view.put("pending_outbox", outboxRepository.countByStatus(SourceOutboxEntity.STATUS_PENDING));
        view.put("open_gaps", gapRepository.countByRecoveryState(
                com.driftwatch.persistence.SourceGapEntity.STATE_OPEN));
        view.put("last_error", state.getLastError());
        return view;
    }

    private String status(CollectorStateEntity state, boolean lost) {
        if (lost) {
            return "LOST";
        }
        if (CollectorStateEntity.STATUS_BACKOFF.equals(state.getStatus())) {
            return "BACKOFF";
        }
        if (CollectorStateEntity.STATUS_ERROR.equals(state.getStatus())) {
            return "ERROR";
        }
        // A successful poll that found nothing new is QUIET, not running: the last event is not
        // newer than the last successful poll (guide 7.2).
        if (state.getLastEventAt() == null || state.getLastPollSuccess() == null
                || !state.getLastEventAt().isAfter(state.getLastPollSuccess())) {
            return "QUIET";
        }
        return "RUNNING";
    }
}
