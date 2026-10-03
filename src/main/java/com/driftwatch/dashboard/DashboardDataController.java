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
import java.util.Map;

@RestController
@RequestMapping("/api/v1/dashboard/api")
public class DashboardDataController {

    private final com.driftwatch.config.WsTicketService wsTicketService;
    private final RawEventRepository rawEventRepository;
    private final QualityAlertRepository alertRepository;
    private final SourceHealthRepository sourceHealthRepository;
    private final SourceHealthService sourceHealthService;

    public DashboardDataController(com.driftwatch.config.WsTicketService wsTicketService,
                                   RawEventRepository rawEventRepository,
                                   QualityAlertRepository alertRepository,
                                   SourceHealthRepository sourceHealthRepository,
                                   SourceHealthService sourceHealthService) {
        this.wsTicketService = wsTicketService;
        this.rawEventRepository = rawEventRepository;
        this.alertRepository = alertRepository;
        this.sourceHealthRepository = sourceHealthRepository;
        this.sourceHealthService = sourceHealthService;
    }

    // Health is refreshed by the scheduler; a dashboard read stays side-effect free (guide 7.2).
    /** Short-lived ticket for the dashboard WebSocket handshake (admin only). */
    @GetMapping("/ws-ticket")
    public Map<String, Object> wsTicket(java.security.Principal principal) {
        String username = principal == null ? "dashboard" : principal.getName();
        return Map.of("ticket", wsTicketService.issue(username),
                "expires_in_seconds", com.driftwatch.config.WsTicketService.TTL.getSeconds());
    }

    @GetMapping("/summary")
    public DashboardSummary summary() {
        Instant now = Instant.now();
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
