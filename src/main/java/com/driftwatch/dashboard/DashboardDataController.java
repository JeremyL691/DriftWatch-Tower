package com.driftwatch.dashboard;

import com.driftwatch.persistence.QualityAlertRepository;
import com.driftwatch.persistence.RawEventRepository;
import com.driftwatch.persistence.SourceHealthEntity;
import com.driftwatch.persistence.SourceHealthRepository;
import com.driftwatch.source.SourceHealthService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;

@RestController
@RequestMapping("/api/v1/dashboard/api")
public class DashboardDataController {

    private final RawEventRepository rawEventRepository;
    private final QualityAlertRepository alertRepository;
    private final SourceHealthRepository sourceHealthRepository;
    private final SourceHealthService sourceHealthService;

    public DashboardDataController(RawEventRepository rawEventRepository,
                                   QualityAlertRepository alertRepository,
                                   SourceHealthRepository sourceHealthRepository,
                                   SourceHealthService sourceHealthService) {
        this.rawEventRepository = rawEventRepository;
        this.alertRepository = alertRepository;
        this.sourceHealthRepository = sourceHealthRepository;
        this.sourceHealthService = sourceHealthService;
    }

    // TODO: this refresh-on-read pattern works fine for a demo but would be a performance problem
    //   in production — should be moved to a scheduled task or event-driven refresh
    @GetMapping("/summary")
    public DashboardSummary summary() {
        Instant now = Instant.now();
        sourceHealthService.refreshAllAndPersist(now);
        long totalEvents = rawEventRepository.count();
        long activeSources = rawEventRepository.findDistinctSources().size();
        long alertsLast24h = alertRepository.countByCreatedAtAfter(now.minus(Duration.ofHours(24)));
        long unhealthySources = sourceHealthRepository.countByStatusIn(
                java.util.List.of(SourceHealthEntity.STATUS_UNHEALTHY, SourceHealthEntity.STATUS_STALE));
        return new DashboardSummary(totalEvents, activeSources, alertsLast24h, unhealthySources);
    }

    public record DashboardSummary(
            long totalEvents,
            long activeSources,
            long alertsLast24h,
            long unhealthySources
    ) {}
}
