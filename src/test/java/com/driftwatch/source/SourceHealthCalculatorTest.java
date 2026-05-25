package com.driftwatch.source;

import com.driftwatch.persistence.SourceHealthEntity;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SourceHealthCalculatorTest {

    private final SourceHealthCalculator calculator = new SourceHealthCalculator();

    @Test
    void healthyInputsKeepHealthyStatus() {
        double score = calculator.score(new SourceHealthCalculator.Inputs(0.0d, 0.1d, 0.0d, 1, false));
        assertThat(score).isGreaterThan(70.0d);
        assertThat(calculator.status(score, false)).isEqualTo(SourceHealthEntity.STATUS_HEALTHY);
    }

    @Test
    void stalePenaltyDropsStatusToStale() {
        double score = calculator.score(new SourceHealthCalculator.Inputs(0.0d, 0.0d, 0.0d, 0, true));
        assertThat(score).isEqualTo(65.0d);
        assertThat(calculator.status(score, true)).isEqualTo(SourceHealthEntity.STATUS_STALE);
    }

    @Test
    void heavyPenaltiesBecomeUnhealthy() {
        double score = calculator.score(new SourceHealthCalculator.Inputs(0.8d, 0.6d, 0.9d, 5, true));
        assertThat(score).isLessThan(50.0d);
        assertThat(calculator.status(score, true)).isEqualTo(SourceHealthEntity.STATUS_UNHEALTHY);
    }
}
