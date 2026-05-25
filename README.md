# DriftWatch Tower

> Java/Spring Boot streaming data quality observability platform. Uses Kafka Streams to detect schema drift, duplicate events, late arrivals, null spikes, stale sources, and anomaly bursts in real time.

**Status: Round 0 — project skeleton.** Only the Spring Boot app and `/actuator/health` are wired up. Kafka, PostgreSQL, detectors, and the dashboard are built incrementally in later rounds.

## Quick start

```bash
./mvnw spring-boot:run
curl http://localhost:8080/actuator/health
# => {"status":"UP", ...}
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
| 1 | Event contract + PostgreSQL persistence |
| 2 | Kafka ingestion + Docker Compose |
| 3 | Duplicate + late event detectors |
| 4 | Schema drift detector |
| 5 | Null spike + anomaly spike detectors |
| 6 | Source health scoring |
| 7 | Dashboard |
| 8 | Testcontainers integration tests + CI |
| 9 | Portfolio polish |

## Tech stack

Java 21 · Spring Boot 3.3 · Spring Web · Actuator · (Kafka Streams, PostgreSQL, Flyway, JUnit 5, Testcontainers — added in later rounds)
