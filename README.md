# DriftWatch Tower

> Java/Spring Boot streaming data quality observability platform. Uses Kafka Streams to detect schema drift, duplicate events, late arrivals, null spikes, stale sources, and anomaly bursts in real time.

**Status: Round 1 — event contract + PostgreSQL persistence.** REST ingestion, Flyway-managed `raw_events` table, payload hashing. Kafka, detectors, and the dashboard come in later rounds.

## Prerequisites

- Java 21+
- PostgreSQL 16 running locally with database `driftwatch` and user `driftwatch` (password `driftwatch`)
  - `brew install postgresql@16 && brew services start postgresql@16`
  - `createuser driftwatch -P` then `createdb -O driftwatch driftwatch`

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
```

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
| 2 | Kafka ingestion + Docker Compose |
| 3 | Duplicate + late event detectors |
| 4 | Schema drift detector |
| 5 | Null spike + anomaly spike detectors |
| 6 | Source health scoring |
| 7 | Dashboard |
| 8 | Testcontainers integration tests + CI |
| 9 | Portfolio polish |

## Tech stack

Java 21 · Spring Boot 3.3 · Spring Web · Actuator · Spring Data JPA · PostgreSQL 16 · Flyway · Jakarta Validation · JUnit 5 · (Kafka Streams, Testcontainers — added in later rounds)
