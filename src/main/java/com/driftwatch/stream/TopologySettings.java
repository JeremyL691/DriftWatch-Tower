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
    private final Duration metricsGrace;
    private final Duration metricsFutureTolerance;
    private final Duration metricsStateRetention;
    private final double nullSpikeThreshold;
    private final int nullSpikeMinSamples;
    private final int anomalyBaselineWindows;
    private final int anomalyMinHistoryWindows;
    private final double anomalySpikeRatio;
    private final int anomalyMinCurrentCount;
    private final String windowPolicyNamespace;

    private com.driftwatch.config.RuntimeConfiguration runtime;

    @Autowired
    public TopologySettings(com.driftwatch.config.RuntimeConfiguration runtime) {
        this(runtime.startupProperties());
        this.runtime = runtime;
    }

    public TopologySettings(DriftwatchProperties properties) {
        this(properties.detector().duplicate().payloadWindow(),
                properties.metrics().windowSize(),
                properties.metrics().grace(),
                properties.metrics().futureTolerance(),
                properties.metrics().stateRetention(),
                properties.detector().nullSpike().threshold(),
                properties.detector().nullSpike().minSamples(),
                properties.detector().anomalySpike().baselineWindows(),
                properties.detector().anomalySpike().minHistoryWindows(),
                properties.detector().anomalySpike().ratioThreshold(),
                properties.detector().anomalySpike().minCurrentCount());
    }

    /** Test-friendly constructor using the documented defaults for grace/tolerance/retention. */
    public TopologySettings(Duration duplicatePayloadWindow,
                            Duration metricsWindowSize,
                            double nullSpikeThreshold,
                            int nullSpikeMinSamples,
                            int anomalyBaselineWindows,
                            int anomalyMinHistoryWindows,
                            double anomalySpikeRatio,
                            int anomalyMinCurrentCount) {
        this(duplicatePayloadWindow, metricsWindowSize, Duration.ofMinutes(10), Duration.ofMinutes(2),
                Duration.ofMinutes(15), nullSpikeThreshold, nullSpikeMinSamples, anomalyBaselineWindows,
                anomalyMinHistoryWindows, anomalySpikeRatio, anomalyMinCurrentCount);
    }

    public TopologySettings(
            Duration duplicatePayloadWindow,
            Duration metricsWindowSize,
            Duration metricsGrace,
            Duration metricsFutureTolerance,
            Duration metricsStateRetention,
            double nullSpikeThreshold,
            int nullSpikeMinSamples,
            int anomalyBaselineWindows,
            int anomalyMinHistoryWindows,
            double anomalySpikeRatio,
            int anomalyMinCurrentCount) {
        this.duplicatePayloadWindow = duplicatePayloadWindow;
        this.metricsWindowSize = metricsWindowSize;
        this.metricsGrace = metricsGrace;
        this.metricsFutureTolerance = metricsFutureTolerance;
        this.metricsStateRetention = metricsStateRetention;
        this.nullSpikeThreshold = nullSpikeThreshold;
        this.nullSpikeMinSamples = nullSpikeMinSamples;
        this.anomalyBaselineWindows = anomalyBaselineWindows;
        this.anomalyMinHistoryWindows = anomalyMinHistoryWindows;
        this.anomalySpikeRatio = anomalySpikeRatio;
        this.anomalyMinCurrentCount = anomalyMinCurrentCount;
        this.windowPolicyNamespace = WindowStateNamespace.fingerprint(this);
    }

    private TopologySettings(TopologySettings startup, com.driftwatch.config.ManagementConfiguration live) {
        this.duplicatePayloadWindow = startup.duplicatePayloadWindow;
        this.metricsWindowSize = startup.metricsWindowSize;
        this.metricsGrace = startup.metricsGrace;
        this.metricsFutureTolerance = startup.metricsFutureTolerance;
        this.metricsStateRetention = startup.metricsStateRetention;
        this.nullSpikeThreshold = live.null_threshold();
        this.nullSpikeMinSamples = live.null_min_samples();
        this.anomalyBaselineWindows = startup.anomalyBaselineWindows;
        this.anomalyMinHistoryWindows = startup.anomalyMinHistoryWindows;
        this.anomalySpikeRatio = live.anomaly_ratio();
        this.anomalyMinCurrentCount = live.anomaly_min_count();
        this.windowPolicyNamespace = startup.windowPolicyNamespace;
    }

    /** One coherent threshold snapshot for a processor invocation; no database/regex compilation. */
    public TopologySettings forEvent() {
        if (runtime == null) return this;
        return new TopologySettings(this, runtime.snapshot().config());
    }

    public String windowPolicyNamespace() { return windowPolicyNamespace; }
    public Duration duplicatePayloadWindow() { return duplicatePayloadWindow; }
    public Duration metricsWindowSize() { return metricsWindowSize; }
    public Duration metricsGrace() { return metricsGrace; }
    public Duration metricsFutureTolerance() { return metricsFutureTolerance; }
    public Duration metricsStateRetention() { return metricsStateRetention; }
    public double nullSpikeThreshold() { return runtime == null ? nullSpikeThreshold : runtime.snapshot().config().null_threshold(); }
    public int nullSpikeMinSamples() { return runtime == null ? nullSpikeMinSamples : runtime.snapshot().config().null_min_samples(); }
    public int anomalyBaselineWindows() { return anomalyBaselineWindows; }
    public int anomalyMinHistoryWindows() { return anomalyMinHistoryWindows; }
    public double anomalySpikeRatio() { return runtime == null ? anomalySpikeRatio : runtime.snapshot().config().anomaly_ratio(); }
    public int anomalyMinCurrentCount() { return runtime == null ? anomalyMinCurrentCount : runtime.snapshot().config().anomaly_min_count(); }
}
