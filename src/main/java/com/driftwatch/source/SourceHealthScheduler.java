package com.driftwatch.source;

import com.driftwatch.config.DriftwatchProperties;
import com.driftwatch.persistence.QualityAlertEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.List;

/**
 * Periodic source-health refresh (execution guide, section 7.2). Runs independently of any
 * dashboard traffic, so a source that goes silent still produces its transition alert, and the
 * scheduler updates health only for the registered sources instead of scanning per event.
 */
@Component
public class SourceHealthScheduler {

    private static final Logger log = LoggerFactory.getLogger(SourceHealthScheduler.class);

    private final SourceHealthService sourceHealthService;
    private final Clock clock;

    public SourceHealthScheduler(SourceHealthService sourceHealthService,
                                 Clock clock,
                                 DriftwatchProperties properties) {
        this.sourceHealthService = sourceHealthService;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${driftwatch.source-health.scheduler-interval:PT30S}", initialDelay = 15_000L)
    public void refresh() {
        List<QualityAlertEntity> alerts = sourceHealthService.refreshScheduled(clock.instant());
        if (!alerts.isEmpty()) {
            log.info("scheduled source health refresh produced {} transition alert(s)", alerts.size());
        }
    }
}
