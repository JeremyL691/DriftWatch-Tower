package com.driftwatch.stream;

import com.driftwatch.config.DriftwatchProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** Tunable thresholds for the quality topology, derived from {@link DriftwatchProperties}. */
@Component
public class TopologySettings {

    private final Duration duplicatePayloadWindow;
    private final Duration metricsWindowSize;
    private final double nullSpikeThreshold;
    private final int nullSpikeMinSamples;
    private final int anomalyBaselineWindows;
    private final int anomalyMinHistoryWindows;
    private final double anomalySpikeRatio;
    private final int anomalyMinCurrentCount;

    @Autowired
    public TopologySettings(DriftwatchProperties properties) {
        this(properties.detector().duplicate().payloadWindow(),
                properties.metrics().windowSize(),
                properties.detector().nullSpike().threshold(),
                properties.detector().nullSpike().minSamples(),
                properties.detector().anomalySpike().baselineWindows(),
                properties.detector().anomalySpike().minHistoryWindows(),
                properties.detector().anomalySpike().ratioThreshold(),
                properties.detector().anomalySpike().minCurrentCount());
    }

    public TopologySettings(
            Duration duplicatePayloadWindow,
            Duration metricsWindowSize,
            double nullSpikeThreshold,
            int nullSpikeMinSamples,
            int anomalyBaselineWindows,
            int anomalyMinHistoryWindows,
            double anomalySpikeRatio,
            int anomalyMinCurrentCount) {
        this.duplicatePayloadWindow = duplicatePayloadWindow;
        this.metricsWindowSize = metricsWindowSize;
        this.nullSpikeThreshold = nullSpikeThreshold;
        this.nullSpikeMinSamples = nullSpikeMinSamples;
        this.anomalyBaselineWindows = anomalyBaselineWindows;
        this.anomalyMinHistoryWindows = anomalyMinHistoryWindows;
        this.anomalySpikeRatio = anomalySpikeRatio;
        this.anomalyMinCurrentCount = anomalyMinCurrentCount;
    }

    public Duration duplicatePayloadWindow() { return duplicatePayloadWindow; }
    public Duration metricsWindowSize() { return metricsWindowSize; }
    public double nullSpikeThreshold() { return nullSpikeThreshold; }
    public int nullSpikeMinSamples() { return nullSpikeMinSamples; }
    public int anomalyBaselineWindows() { return anomalyBaselineWindows; }
    public int anomalyMinHistoryWindows() { return anomalyMinHistoryWindows; }
    public double anomalySpikeRatio() { return anomalySpikeRatio; }
    public int anomalyMinCurrentCount() { return anomalyMinCurrentCount; }
}
