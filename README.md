# DriftWatch Tower

> Java/Spring Boot streaming data quality observability platform. Uses Kafka Streams to detect schema drift, duplicate events, late arrivals, null spikes, stale sources, and anomaly bursts in real time.

**Status: Round 4 — schema drift detector.** Three quality detectors run on every consumed event: duplicate (event_id / payload hash), late event, and schema drift (vs. an inferred per-event_type baseline stored in `schema_versions`). `GET /schemas` exposes the registry; the `schema-drift` demo scenario produces a baseline + drifted payload. Windowed detectors and the dashboard come in later rounds.

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
curl 'http://localhost:8080/alerts?size=10'
curl 'http://localhost:8080/alerts?type=SCHEMA_DRIFT'

# Schema registry
curl http://localhost:8080/schemas
curl http://localhost:8080/schemas/demo_schema_event
```

## Detectors (Round 3)

| Detector | Triggers | Severity |
|---|---|---|
| `DuplicateDetector` | Same `event_id` already persisted, or same `payload_hash` within `driftwatch.detector.duplicate.payload-window` (default 5m) under a different `event_id` | `INFO` |
| `LateEventDetector` | `received_at − event_timestamp` > `driftwatch.detector.late.threshold` (default 5m). `WARN` once >10× threshold. | `INFO`/`WARN` |
| `SchemaDriftDetector` | Inferred payload schema differs from the ACTIVE baseline for that `event_type` (missing / added / type-changed fields). New event_types are baselined silently. | `WARN` |

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

`docker-compose.yml` is a placeholder. Docker is **not required** for Round 0. Services are introduced in Round 2 (Kafka ingestion).

## Roadmap

Full multi-round build plan: [docs/DriftWatch_Tower_Project_Guide.md](docs/DriftWatch_Tower_Project_Guide.md).

| Round | Goal |
|------:|------|
| 0 | Project skeleton ✅ |
| 1 | Event contract + PostgreSQL persistence ✅ |
| 2 | Kafka ingestion + Docker Compose ✅ |
| 3 | Duplicate + late event detectors ✅ |
| 4 | Schema drift detector ✅ |
| 5 | Null spike + anomaly spike detectors |
| 6 | Source health scoring |
| 7 | Dashboard |
| 8 | Testcontainers integration tests + CI |
| 9 | Portfolio polish |

## Tech stack

Java 21 · Spring Boot 3.3 · Spring Web · Actuator · Spring Data JPA · PostgreSQL 16 · Flyway · Jakarta Validation · Spring Kafka · Apache Kafka 4 (KRaft) · JUnit 5 · spring-kafka-test (`@EmbeddedKafka`) · Awaitility · (Kafka Streams, Testcontainers — added in later rounds)
