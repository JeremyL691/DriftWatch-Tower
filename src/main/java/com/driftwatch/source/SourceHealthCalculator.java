package com.driftwatch.source;

import com.driftwatch.persistence.SourceHealthEntity;
import org.springframework.stereotype.Component;

@Component
public class SourceHealthCalculator {

    public double score(Inputs inputs) {
        double score = 100.0d;
        score -= Math.min(inputs.duplicateRate() * 35.0d, 35.0d);
        score -= Math.min(inputs.lateEventRate() * 25.0d, 25.0d);
        score -= Math.min(inputs.nullRate() * 25.0d, 25.0d);
        score -= Math.min(inputs.recentAlerts() * 5.0d, 20.0d);
        if (inputs.stale()) {
            score -= 35.0d;
        }
        return Math.max(0.0d, Math.min(100.0d, score));
    }

    public String status(double score, boolean stale) {
        if (stale) {
            return SourceHealthEntity.STATUS_STALE;
        }
        return score < 60.0d ? SourceHealthEntity.STATUS_UNHEALTHY : SourceHealthEntity.STATUS_HEALTHY;
    }

    public record Inputs(
            double duplicateRate,
            double lateEventRate,
            double nullRate,
            long recentAlerts,
            boolean stale
    ) {}
}
