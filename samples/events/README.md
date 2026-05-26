# Sample Events

Reusable `DataEvent` payloads for hand-testing the ingestion endpoint and detectors. POST any of them to `/events`:

```bash
curl -X POST http://localhost:8080/events \
  -H 'Content-Type: application/json' \
  -d @samples/events/market_tick.json
```

| File | What it exercises |
|---|---|
| [`market_tick.json`](market_tick.json) | Clean baseline event — no detector should fire. Useful as a smoke test. |
| [`schema_drift_baseline.json`](schema_drift_baseline.json) | Establishes the baseline schema for `demo_schema_event` (send this first). |
| [`schema_drift_changed.json`](schema_drift_changed.json) | Same event type with a changed payload shape — triggers `SchemaDriftDetector`. |
| [`late_event.json`](late_event.json) | `event_timestamp` set far in the past — triggers `LateEventDetector`. |

For a full demo without crafting events by hand, use the scripted scenarios instead:

```bash
curl -X POST http://localhost:8080/demo/run-scenario/mixed-incident
```
