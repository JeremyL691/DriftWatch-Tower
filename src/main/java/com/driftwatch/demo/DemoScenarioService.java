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

    public record ScenarioRun(String scenario, String description, List<String> eventIds) {}
}
