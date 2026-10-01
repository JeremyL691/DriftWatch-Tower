# Sample Events

Synthetic `DataEvent` payloads for hand-testing the ingestion endpoint and detectors. POST any of them to `/api/v1/events`. Their timestamps are fixed historical examples; update `event_timestamp` in a temporary request if you want a fresh event. Do not treat these files as a live data source.

```bash
curl -X POST http://localhost:8080/api/v1/events \
  -H 'Content-Type: application/json' \
  -d @samples/events/market_tick.json
```

| File | What it exercises |
|---|---|
| [`market_tick.json`](market_tick.json) | Example market-tick shape. Its historical timestamp can fire the late-event detector; use a fresh timestamp and a new event ID for a clean smoke test. |
| [`schema_drift_baseline.json`](schema_drift_baseline.json) | Establishes the baseline schema for `demo_schema_event` (send this first). |
| [`schema_drift_changed.json`](schema_drift_changed.json) | Same event type with a changed payload shape — triggers `SchemaDriftDetector`. |
| [`late_event.json`](late_event.json) | `event_timestamp` set far in the past — triggers `LateEventDetector`. |

For a full demo without crafting events by hand, use the scripted scenarios instead:

```bash
curl -X POST http://localhost:8080/api/v1/demo/run-scenario/mixed-incident
```
