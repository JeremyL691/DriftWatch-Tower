# DriftWatch Tower

> Java/Spring Boot streaming data quality observability platform. Uses Kafka-backed event processing to detect schema drift, duplicate events, late arrivals, null spikes, stale sources, and anomaly bursts in real time.

**Status: Round 5 — window metrics + spike detectors.** Five quality detectors run on every consumed event: duplicate, late event, schema drift, null spike, and anomaly spike. `GET /metrics/windows` exposes persisted event-count and null-rate windows, and new demo scenarios exercise `null-spike` and `anomaly-spike`.

## Prerequisites

- Java 21+
- PostgreSQL 16 with database `driftwatch` and user `driftwatch` (password `driftwatch`)
  - `brew install postgresql@16 && brew services start postgresql@16`
  - `createuser driftwatch -P` then `createdb -O driftwatch driftwatch`
- Kafka (KRaft) on `localhost:9092`
  - `brew install kafka && brew services start kafka`
  - Or run the bundled stack: `docker compose up -d` (requires Docker Desktop)

## Quick start

```bash
./mvnw spring-boot:run

# Health
curl http://localhost:8080/actuator/health

# Ingest an event
curl -X POST http://localhost:8080/events \
  -H 'Content-Type: application/json' \
  -d '{"event_id":"evt-001","source":"binance","event_type":"market_tick",
       "event_timestamp":"2026-05-25T08:30:00Z",
       "payload":{"symbol":"BTC/USDT","bid":108000.1,"ask":108002.4}}'

# List recent events (most-recent first)
curl 'http://localhost:8080/events/recent?size=10'

# Run a demo scenario and inspect resulting alerts
curl -X POST http://localhost:8080/demo/run-scenario/duplicate-events
curl -X POST http://localhost:8080/demo/run-scenario/late-events
curl -X POST http://localhost:8080/demo/run-scenario/schema-drift
curl -X POST http://localhost:8080/demo/run-scenario/null-spike
curl -X POST http://localhost:8080/demo/run-scenario/anomaly-spike
curl 'http://localhost:8080/alerts?size=10'
curl 'http://localhost:8080/alerts?type=SCHEMA_DRIFT'

# Schema registry
curl http://localhost:8080/schemas
curl http://localhost:8080/schemas/demo_schema_event

# Metric windows
curl 'http://localhost:8080/metrics/windows?eventType=demo_null_event'
```

## Detectors (Round 5)

| Detector | Triggers | Severity |
|---|---|---|
| `DuplicateDetector` | Same `event_id` already persisted, or same `payload_hash` within `driftwatch.detector.duplicate.payload-window` (default 5m) under a different `event_id` | `INFO` |
| `LateEventDetector` | `received_at − event_timestamp` > `driftwatch.detector.late.threshold` (default 5m). `WARN` once >10× threshold. | `INFO`/`WARN` |
| `SchemaDriftDetector` | Inferred payload schema differs from the ACTIVE baseline for that `event_type` (missing / added / type-changed fields). New event_types are baselined silently. | `WARN` |
| `NullSpikeDetector` | Field-level null or missing rate exceeds `driftwatch.detector.null-spike.threshold` within the current metric window once enough samples accumulate. | `WARN` |
| `AnomalySpikeDetector` | Current event-count window exceeds the average of recent completed windows by `driftwatch.detector.anomaly-spike.ratio-threshold`. | `WARN` |

## Tests

```bash
./mvnw test
```

## Project layout

```
src/main/java/com/driftwatch/
  DriftWatchApplication.java   # Spring Boot entry point
  api/            # REST controllers (Round 1+)
  config/         # Spring configuration
  event/          # DataEvent model + hashing (Round 1)
  quality/        # Detectors (Rounds 3–6)
  stream/         # Kafka Streams topology (Round 2+)
  persistence/    # JPA entities + repositories (Round 1+)
  demo/           # Demo scenarios (Round 3+)
  dashboard/      # Dashboard views (Round 7)
```

## Docker

`docker-compose.yml` brings up PostgreSQL and Kafka locally. The optional `app` profile expects a `Dockerfile` if you want to run the Spring Boot service inside Compose.

## Roadmap

Full multi-round build plan: [docs/DriftWatch_Tower_Project_Guide.md](docs/DriftWatch_Tower_Project_Guide.md).

| Round | Goal |
|------:|------|
| 0 | Project skeleton ✅ |
| 1 | Event contract + PostgreSQL persistence ✅ |
| 2 | Kafka ingestion + Docker Compose ✅ |
| 3 | Duplicate + late event detectors ✅ |
| 4 | Schema drift detector ✅ |
| 5 | Null spike + anomaly spike detectors + metric windows ✅ |
| 6 | Source health scoring |
| 7 | Dashboard |
| 8 | Testcontainers integration tests + CI |
| 9 | Portfolio polish |

## Tech stack

Java 21 · Spring Boot 3.3 · Spring Web · Actuator · Spring Data JPA · PostgreSQL 16 · Flyway · Jakarta Validation · Spring Kafka · Apache Kafka 4 (KRaft) · JUnit 5 · spring-kafka-test (`@EmbeddedKafka`) · Awaitility · (Testcontainers, dashboard, source health — added in later rounds)
