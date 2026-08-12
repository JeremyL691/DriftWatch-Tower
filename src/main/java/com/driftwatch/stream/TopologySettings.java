package com.driftwatch.stream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** Tunable thresholds for the quality topology, collected from the scattered application.yml keys. */
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

    public TopologySettings(
            @Value("${driftwatch.detector.duplicate.payload-window:PT5M}") Duration duplicatePayloadWindow,
            @Value("${driftwatch.metrics.window-size:PT1M}") Duration metricsWindowSize,
            @Value("${driftwatch.detector.null-spike.threshold:0.6}") double nullSpikeThreshold,
            @Value("${driftwatch.detector.null-spike.min-samples:3}") int nullSpikeMinSamples,
            @Value("${driftwatch.detector.anomaly-spike.baseline-windows:2}") int anomalyBaselineWindows,
            @Value("${driftwatch.detector.anomaly-spike.min-history-windows:2}") int anomalyMinHistoryWindows,
            @Value("${driftwatch.detector.anomaly-spike.ratio-threshold:3.0}") double anomalySpikeRatio,
            @Value("${driftwatch.detector.anomaly-spike.min-current-count:5}") int anomalyMinCurrentCount) {
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
