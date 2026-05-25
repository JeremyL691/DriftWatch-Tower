package com.driftwatch.demo;

import com.driftwatch.event.DataEvent;
import com.driftwatch.event.RawEventProducer;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class DemoScenarioService {

    private final RawEventProducer producer;

    public DemoScenarioService(RawEventProducer producer) {
        this.producer = producer;
    }

    public ScenarioRun runDuplicateEvents() {
        Instant now = Instant.now();
        String evtId = "demo-dup-" + UUID.randomUUID();
        DataEvent first = new DataEvent(evtId, "demo-api", "market_tick", now,
                Map.of("symbol", "BTC/USDT", "bid", 108000.1, "ask", 108002.4));
        producer.publish(first);
        producer.publish(first);
        producer.publish(first);
        return new ScenarioRun("duplicate-events",
                "Published the same event_id 3 times — expect DUPLICATE_EVENT alerts.",
                List.of(evtId));
    }

    public ScenarioRun runSchemaDrift() {
        Instant now = Instant.now();
        String baseEvt = "demo-schema-" + UUID.randomUUID();
        DataEvent baseline = new DataEvent(baseEvt + "-base", "demo-api", "demo_schema_event", now,
                Map.of("symbol", "BTC/USDT", "bid", 100.0, "ask", 101.0, "trace", baseEvt));
        producer.publish(baseline);
        try { Thread.sleep(500); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        DataEvent drift = new DataEvent(baseEvt + "-drift", "demo-api", "demo_schema_event", now,
                Map.of("symbol", "BTC/USDT", "bid", "100.0", "spread", 1.0, "trace", baseEvt));
        producer.publish(drift);
        return new ScenarioRun("schema-drift",
                "Published baseline {symbol,bid,ask} then a drifted payload where bid became STRING, "
                        + "ask was dropped, and spread was added — expect a SCHEMA_DRIFT alert.",
                List.of(baseline.eventId(), drift.eventId()));
    }

    public ScenarioRun runLateEvents() {
        Instant now = Instant.now();
        String evtId = "demo-late-" + UUID.randomUUID();
        DataEvent late = new DataEvent(evtId, "demo-api", "market_tick",
                now.minusSeconds(600),
                Map.of("symbol", "ETH/USDT", "bid", 3500.0, "ask", 3502.0, "trace", evtId));
        producer.publish(late);
        return new ScenarioRun("late-events",
                "Published an event with event_timestamp 10 minutes in the past — expect a LATE_EVENT alert.",
                List.of(evtId));
    }

    public ScenarioRun runNullSpike() {
        Instant now = Instant.now();
        String trace = "demo-null-" + UUID.randomUUID();
        DataEvent baseline = new DataEvent(trace + "-base", "demo-api", "demo_null_event", now,
                Map.of("symbol", "BTC/USDT", "bid", 108000.1, "ask", 108002.4, "trace", trace, "seq", 0));
        producer.publish(baseline);

        List<String> eventIds = new java.util.ArrayList<>();
        eventIds.add(baseline.eventId());
        for (int i = 1; i <= 5; i++) {
            DataEvent spike = new DataEvent(trace + "-null-" + i, "demo-api", "demo_null_event", now.plusSeconds(i),
                    nullSpikePayload(trace, i));
            producer.publish(spike);
            eventIds.add(spike.eventId());
        }
        return new ScenarioRun("null-spike",
                "Published a baseline followed by repeated null ask values in the same metric window — expect a NULL_SPIKE alert.",
                eventIds);
    }

    public ScenarioRun runAnomalySpike() {
        Instant now = Instant.now();
        String trace = "demo-anomaly-" + UUID.randomUUID();
        List<String> eventIds = new java.util.ArrayList<>();

        publishAnomalyWindow(trace, now.minusSeconds(120), 2, eventIds);
        publishAnomalyWindow(trace, now.minusSeconds(60), 2, eventIds);
        publishAnomalyWindow(trace, now, 8, eventIds);

        return new ScenarioRun("anomaly-spike",
                "Published two calm windows followed by a burst in the current window — expect an ANOMALY_SPIKE alert.",
                eventIds);
    }

    private void publishAnomalyWindow(String trace, Instant baseTime, int count, List<String> eventIds) {
        for (int i = 0; i < count; i++) {
            String eventId = trace + "-" + baseTime.getEpochSecond() + "-" + i;
            DataEvent event = new DataEvent(eventId, "demo-api", "demo_anomaly_event", baseTime.plusSeconds(i),
                    Map.of("symbol", "BTC/USDT", "bid", 108000.0 + i, "ask", 108001.0 + i, "trace", trace, "seq", eventId));
            producer.publish(event);
            eventIds.add(eventId);
        }
    }

    private Map<String, Object> nullSpikePayload(String trace, int seq) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("symbol", "BTC/USDT");
        payload.put("bid", 108000.1 + seq);
        payload.put("ask", null);
        payload.put("trace", trace);
        payload.put("seq", seq);
        return payload;
    }

    public record ScenarioRun(String scenario, String description, List<String> eventIds) {}
}
