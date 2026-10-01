package com.driftwatch.operations;

import com.driftwatch.persistence.BaselineOutboxEntity;
import com.driftwatch.persistence.BaselineOutboxRepository;
import com.driftwatch.persistence.DeadLetterRecordEntity;
import com.driftwatch.persistence.DeadLetterRecordRepository;
import com.driftwatch.persistence.SourceOutboxEntity;
import com.driftwatch.persistence.SourceOutboxRepository;
import com.driftwatch.quality.AlertType;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Low-cardinality metrics for the operations surface (execution guide, section 7.3).
 *
 * <p>Only bounded label values are used (detector type, outcome, source); event ids, ingestion ids
 * and exception text never become labels. Backlog and retention values are gauges so a scrape is
 * always current without extra bookkeeping.
 */
@Component
public class DriftwatchMetrics {

    private final MeterRegistry registry;
    private final Timer ingestionAckDuration;
    private final Counter ingestionFailures;
    private final Timer processingDuration;
    private final Counter processingFailures;
    private final Counter collectorPolls;
    private final Counter collectorFailures;
    private final AtomicLong collectorUpstreamLagSeconds = new AtomicLong(0);
    private final Counter alertsFired;
    private final AtomicLong rowsPruned = new AtomicLong(0);
    private final AtomicLong lastRetentionSuccessEpochSeconds = new AtomicLong(0);
    private final DistributionSummary eventsPerBatch;

    public DriftwatchMetrics(MeterRegistry registry,
                             SourceOutboxRepository sourceOutboxRepository,
                             DeadLetterRecordRepository deadLetterRepository,
                             BaselineOutboxRepository baselineOutboxRepository,
                             Clock clock) {
        this.registry = registry;
        this.ingestionAckDuration = Timer.builder("driftwatch_ingestion_ack_duration_seconds")
                .description("HTTP request to broker acknowledgement for one ingest")
                .publishPercentiles(0.5, 0.95, 0.99)
                .publishPercentileHistogram(true)
                .minimumExpectedValue(Duration.ofMillis(1))
                .maximumExpectedValue(Duration.ofSeconds(30))
                .register(registry);
        this.ingestionFailures = Counter.builder("driftwatch_ingestion_failures_total")
                .description("Ingest attempts that ended unconfirmed")
                .register(registry);
        // Percentile histograms, not only live quantiles, so a load run can difference bucket
        // counts across its own window and report a p95 that belongs to that window alone.
        this.processingDuration = Timer.builder("driftwatch_processing_duration_seconds")
                .description("received_at to database commit for one processed event")
                .publishPercentiles(0.5, 0.95, 0.99)
                .publishPercentileHistogram(true)
                .minimumExpectedValue(Duration.ofMillis(1))
                .maximumExpectedValue(Duration.ofSeconds(30))
                .register(registry);
        this.processingFailures = Counter.builder("driftwatch_processing_failures_total")
                .description("Persistence attempts that failed (including retries)")
                .register(registry);
        this.collectorPolls = Counter.builder("driftwatch_collector_polls_total")
                .description("Poll rounds by outcome")
                .tag("outcome", "started")
                .register(registry);
        this.collectorFailures = Counter.builder("driftwatch_collector_failures_total")
                .description("Poll rounds that failed by reason class")
                .register(registry);
        this.alertsFired = Counter.builder("driftwatch_alerts_fired_total")
                .description("Alerts persisted, labelled by detector type and severity")
                .register(registry);
        this.eventsPerBatch = DistributionSummary.builder("driftwatch_ingestion_batch_size")
                .description("Accepted events per batch request")
                .register(registry);

        Gauge.builder("driftwatch_source_outbox_pending", sourceOutboxRepository,
                        repository -> repository.countByStatus(SourceOutboxEntity.STATUS_PENDING))
                .description("Source outbox rows waiting for broker acknowledgement")
                .register(registry);
        Gauge.builder("driftwatch_dead_letters_pending", deadLetterRepository,
                        repository -> repository.countByRecoveryState(DeadLetterRecordEntity.STATE_OPEN))
                .description("Dead letters that are not recovered yet")
                .register(registry);
        Gauge.builder("driftwatch_baseline_outbox_pending", baselineOutboxRepository,
                        repository -> repository.countByStatus(BaselineOutboxEntity.STATUS_PENDING))
                .description("Baseline changes waiting for the compacted topic")
                .register(registry);
        Gauge.builder("driftwatch_collector_upstream_lag_seconds", collectorUpstreamLagSeconds,
                        AtomicLong::doubleValue)
                .description("Age of the newest upstream event, not processing latency")
                .register(registry);
        Gauge.builder("driftwatch_retention_rows_pruned", rowsPruned, AtomicLong::doubleValue)
                .description("Rows removed by retention since start")
                .register(registry);
        Gauge.builder("driftwatch_retention_last_success_timestamp_seconds", lastRetentionSuccessEpochSeconds,
                        AtomicLong::doubleValue)
                .description("Unix time of the last successful retention pass")
                .register(registry);
    }

    /** Records one ingest: ack duration in seconds, plus a failure when unconfirmed. */
    public void recordIngest(Duration ackDuration, boolean confirmed) {
        ingestionAckDuration.record(ackDuration.toNanos(), TimeUnit.NANOSECONDS);
        if (!confirmed) {
            ingestionFailures.increment();
        }
    }

    public void recordProcessing(Duration fromReceivedToCommit) {
        processingDuration.record(fromReceivedToCommit.toNanos(), TimeUnit.NANOSECONDS);
    }

    public void recordProcessingFailure() {
        processingFailures.increment();
    }

    public void recordCollectorPoll(String outcome) {
        Counter.builder("driftwatch_collector_polls_total")
                .description("Poll rounds by outcome")
                .tag("outcome", outcome)
                .register(registry)
                .increment();
    }

    public void recordCollectorFailure(String reasonClass) {
        Counter.builder("driftwatch_collector_failures_total")
                .description("Poll rounds that failed by reason class")
                .tag("reason", reasonClass)
                .register(registry)
                .increment();
    }

    public void recordCollectorUpstreamLag(Instant lastEventAt) {
        collectorUpstreamLagSeconds.set(lastEventAt == null ? 0
                : Math.max(0, Duration.between(lastEventAt, Instant.now()).getSeconds()));
    }

    public void recordAlert(AlertType type, String severity) {
        Counter.builder("driftwatch_alerts_fired_total")
                .description("Alerts persisted, labelled by detector type and severity")
                .tag("detector", type.name())
                .tag("severity", severity)
                .register(registry)
                .increment();
    }

    public void recordBatchSize(int size) {
        eventsPerBatch.record(size);
    }

    public void recordRetention(long pruned) {
        rowsPruned.addAndGet(pruned);
        lastRetentionSuccessEpochSeconds.set(Instant.now().getEpochSecond());
    }
}
