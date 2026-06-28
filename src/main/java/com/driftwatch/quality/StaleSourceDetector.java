package com.driftwatch.quality;

import com.driftwatch.source.SourceHealthService;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Wraps {@link SourceHealthService#refreshAllAndPersist} so source-health / stale-source
 * detection is exposed as a first-class {@link QualityDetector}. {@code QualityProcessor}
 * already invokes the service directly; this detector participates in the same pipeline
 * for symmetry and discoverability. The service is idempotent (only emits STALE_SOURCE on
 * the not-stale → stale transition), so the second call is a no-op for alert persistence.
 */
@Component
@Order(80)
public class StaleSourceDetector implements QualityDetector {

    private final SourceHealthService sourceHealthService;

    public StaleSourceDetector(SourceHealthService sourceHealthService) {
        this.sourceHealthService = sourceHealthService;
    }

    @Override
    public List<DraftAlert> detect(DetectionContext ctx) {
        // Side effect: refresh source-health rows and (idempotently) emit any new stale alerts.
        sourceHealthService.refreshAllAndPersist(ctx.receivedAt());
        // Drafts already persisted by the service; nothing to add to the pipeline.
        return List.of();
    }
}