# DriftWatch Tower Project Guide

Project name: `DriftWatch Tower`

Recommended repository name: `driftwatch-tower`

One-line positioning:

> DriftWatch Tower is a Java/Spring Boot streaming data quality observability platform that uses Kafka Streams to detect schema drift, duplicate events, late arrivals, null spikes, stale sources, and anomaly bursts in real time.

## 1. Why This Project Exists

This project is designed to strengthen a portfolio that already includes Python AI/data products such as knowledge-base ingestion, RAG retrieval, crypto/news intelligence, RSS processing, PDF parsing, and AI-assisted workflows.

The goal is not to build another generic Java CRUD app. The goal is to prove that the developer can build a real data infrastructure service in Java:

- event-driven ingestion
- stream processing
- stateful quality checks
- source health monitoring
- schema drift detection
- alert evidence storage
- dashboard/API observability
- integration tests with Kafka and PostgreSQL

Recruiter-facing story:

> I built AI/data applications in Python, then built a Java streaming observability layer that monitors data pipeline quality in real time.

## 2. Final Product Vision

At the end of development, DriftWatch Tower should feel like a small but serious internal data platform tool.

It should let a user:

1. Start the system locally with Docker Compose.
2. Send sample event streams into Kafka.
3. Watch the system detect data quality problems in real time.
4. See quality alerts with evidence.
5. Inspect source freshness and health scores.
6. View schema versions and drift events.
7. Run demo scenarios from an API or dashboard.
8. Run tests that prove Kafka/PostgreSQL behavior works.

The finished repository should include:

- Java 21 Spring Boot app
- Kafka + Kafka Streams pipeline
- PostgreSQL persistence
- Flyway database migrations
- REST API
- Dashboard
- Demo event generator
- Docker Compose
- JUnit + Testcontainers tests
- README with architecture diagram, screenshots, setup commands, and demo script
- GitHub Actions CI

## 3. What This Project Is Not

Do not turn the MVP into a broad SaaS product.

Do not build:

- user accounts
- login/auth
- payment/subscription logic
- complex role permissions
- cloud deployment as a core requirement
- a large React app before the backend is useful
- a generic monitoring dashboard with no data quality logic
- AI features that distract from the Java streaming/data engineering core

AI can be mentioned in the larger portfolio story, but DriftWatch Tower itself should mainly prove Java backend and streaming data engineering ability.

## 4. Core Use Cases

DriftWatch Tower receives event data from different systems. The event streams can represent real systems from the existing portfolio:

- `sourcehero`: document ingestion, chunking, RSS/web/PDF pipeline events
- `alphabrief`: market ticks, news ingestion, briefing generation, alert events
- `binance`: market tick source
- `coindesk`: RSS article source
- `demo-api`: synthetic demo source

Example event types:

- `market_tick`
- `rss_article`
- `document_ingestion`
- `pipeline_run`
- `briefing_generated`

Example event envelope:

```json
{
  "event_id": "evt-001",
  "source": "binance",
  "event_type": "market_tick",
  "event_timestamp": "2026-05-25T08:30:00Z",
  "payload": {
    "symbol": "BTC/USDT",
    "bid": 108000.1,
    "ask": 108002.4,
    "last": 108001.0
  }
}
```

## 5. System Architecture

```text
Demo Producers / REST Ingestion
        |
        v
Kafka Topic: raw-events
        |
        v
Kafka Streams Quality Processor
        |
        +--> Duplicate Detector
        +--> Late Event Detector
        +--> Schema Drift Detector
        +--> Null Spike Detector
        +--> Source Freshness Detector
        +--> Anomaly Spike Detector
        |
        v
Kafka Topics:
  - quality-alerts
  - source-health-events
        |
        v
PostgreSQL
  - raw_events
  - quality_alerts
  - source_health
  - schema_versions
  - metric_windows
        |
        v
Spring Boot REST API
        |
        v
Dashboard
```

## 6. Technology Stack

Use this stack unless there is a strong reason not to:

- Java 21
- Spring Boot 3
- Spring Web
- Spring Kafka
- Kafka Streams
- PostgreSQL
- Flyway
- Docker Compose
- JUnit 5
- Testcontainers
- Actuator
- Micrometer
- Thymeleaf or simple static HTML dashboard
- Optional later: Prometheus/Grafana

Do not start with Apache Flink. Kafka Streams is the right first implementation because it keeps the project Java-native, easier to run locally, and easier to explain.

## 7. Data Quality Detectors

The first complete version should include six detectors.

### 7.1 Schema Drift Detector

Purpose:

- Detect when an event type changes shape unexpectedly.

Detect:

- missing expected field
- new unexpected field
- field type changed

Example:

- `market_tick.payload.bid` was a number, then appears as a string.

Output:

- `SCHEMA_DRIFT` quality alert
- evidence includes expected schema, observed schema, changed fields, event id

### 7.2 Duplicate Event Detector

Purpose:

- Detect repeated events.

Detect:

- repeated `event_id`
- repeated payload hash within a recent window

Output:

- `DUPLICATE_EVENT` quality alert
- evidence includes duplicate key, first seen time, repeated event id

### 7.3 Late Event Detector

Purpose:

- Detect events that arrive too late.

Detect:

- `processing_time - event_timestamp > threshold`

Default threshold:

- 5 minutes

Output:

- `LATE_EVENT` quality alert
- evidence includes event timestamp, received timestamp, lateness seconds

### 7.4 Null Spike Detector

Purpose:

- Detect sudden missing-value problems.

Detect:

- null or missing field rate exceeds threshold within a time window

Default examples:

- `market_tick.payload.bid` null rate > 20%
- `rss_article.payload.url` null rate > 10%

Output:

- `NULL_SPIKE` quality alert
- evidence includes field path, window, null count, total count, null rate

### 7.5 Source Freshness Detector

Purpose:

- Detect stale data sources.

Detect:

- source has not emitted events within expected interval

Default examples:

- market source stale after 5 minutes
- RSS source stale after 30 minutes
- document pipeline source stale after 24 hours

Output:

- `STALE_SOURCE` quality alert
- source status becomes `stale` or `unhealthy`

### 7.6 Anomaly Spike Detector

Purpose:

- Detect event volume or numeric metric spikes.

Detect:

- event count suddenly much higher/lower than rolling baseline
- market tick value outside reasonable rolling range
- ingestion failure count spikes

MVP approach:

- Use simple rolling averages and thresholds.
- Do not overcomplicate with ML in v1.

Output:

- `ANOMALY_SPIKE` quality alert
- evidence includes baseline, current value, ratio, window

## 8. Database Model

### `raw_events`

Stores accepted events.

Fields:

- `id`
- `event_id`
- `source`
- `event_type`
- `event_timestamp`
- `received_at`
- `payload_json`
- `payload_hash`
- `quality_status`

### `quality_alerts`

Stores detector outputs.

Fields:

- `id`
- `alert_type`
- `severity`
- `source`
- `event_type`
- `field_path`
- `message`
- `evidence_json`
- `created_at`
- `resolved_at`

### `source_health`

Stores source-level reliability.

Fields:

- `source`
- `status`
- `last_seen_at`
- `events_last_5m`
- `events_last_1h`
- `duplicate_rate`
- `late_event_rate`
- `null_rate`
- `health_score`
- `updated_at`

### `schema_versions`

Stores observed schemas per event type.

Fields:

- `id`
- `event_type`
- `schema_hash`
- `schema_json`
- `first_seen_at`
- `last_seen_at`
- `status`

### `metric_windows`

Stores window-level metrics.

Fields:

- `id`
- `source`
- `event_type`
- `window_start`
- `window_end`
- `metric_name`
- `metric_value`

## 9. REST API

MVP endpoints:

```text
POST /events
GET  /events/recent
GET  /alerts
GET  /alerts/{id}
POST /alerts/{id}/resolve
GET  /sources/health
GET  /sources/{source}/health
GET  /schemas
GET  /schemas/{eventType}
GET  /metrics/windows
POST /demo/seed
POST /demo/run-scenario/{scenarioName}
GET  /actuator/health
```

Demo scenarios:

```text
normal-flow
schema-drift
duplicate-events
late-events
null-spike
stale-source
anomaly-spike
mixed-incident
```

## 10. Dashboard Requirements

Keep the dashboard simple but useful. It can be Thymeleaf, static HTML, or a small frontend served by Spring Boot.

Pages:

### Overview

Show:

- total events processed
- active sources
- alerts in last 24h
- unhealthy sources
- latest detector activity

### Alerts

Show:

- alert type
- severity
- source
- event type
- field path
- message
- created time
- evidence details

### Source Health

Show:

- source name
- status
- last seen time
- event volume
- duplicate rate
- late event rate
- null rate
- health score

### Schema Registry

Show:

- event type
- current schema hash
- first seen time
- last seen time
- schema status
- drift history

### Demo Scenarios

Show buttons for:

- normal flow
- schema drift
- duplicate events
- late events
- null spike
- stale source
- anomaly spike
- mixed incident

After a scenario runs, the dashboard should make it obvious which alerts were created.

## 11. Development Layers and Rounds

Build the project in layers. Each round should leave the repository in a working state.

## Round 0: Project Skeleton

Goal:

- Create a clean Java project that starts reliably.

Build:

- Spring Boot project
- Java 21 config
- Maven or Gradle
- package structure
- `/actuator/health`
- basic README
- Docker Compose with PostgreSQL and Kafka placeholders

Suggested packages:

```text
com.driftwatch
  api
  config
  event
  quality
  stream
  persistence
  demo
  dashboard
```

Done when:

- app starts locally
- health endpoint works
- tests run

## Round 1: Event Contract and Persistence

Goal:

- Define the canonical event model and store events.

Build:

- `DataEvent` DTO
- payload validation
- `POST /events`
- `GET /events/recent`
- `raw_events` Flyway migration
- `RawEventEntity`
- repository/service layer
- payload hash generation

Done when:

- user can POST an event
- event is stored in PostgreSQL
- recent events API returns stored events
- unit tests cover validation and hashing

## Round 2: Kafka Ingestion

Goal:

- Make events flow through Kafka.

Build:

- Kafka topic `raw-events`
- event producer from `POST /events`
- consumer that persists events if not already persisted
- Docker Compose for app + Kafka + PostgreSQL
- local run instructions

Done when:

- posting `/events` publishes to Kafka
- event is consumed and stored
- Docker Compose starts the required services

## Round 3: First Detectors

Goal:

- Implement the simplest real-time quality logic.

Build:

- duplicate detector
- late event detector
- `quality_alerts` table
- `GET /alerts`
- `POST /demo/run-scenario/duplicate-events`
- `POST /demo/run-scenario/late-events`

Done when:

- duplicate scenario creates a duplicate alert
- late scenario creates a late alert
- alerts include evidence JSON

## Round 4: Schema Drift

Goal:

- Add a detector that makes the project stand out.

Build:

- schema inference from JSON payload
- schema hash generation
- `schema_versions` table
- schema registry service
- schema drift alert generation
- `GET /schemas`
- `POST /demo/run-scenario/schema-drift`

Done when:

- normal events establish a baseline schema
- changed payload creates a `SCHEMA_DRIFT` alert
- schema page/API shows versions

## Round 5: Window Metrics

Goal:

- Add streaming/windowed quality checks.

Build:

- null spike detector
- anomaly spike detector
- `metric_windows` table
- event count windows
- field null-rate windows
- `GET /metrics/windows`
- demo scenarios for null spike and anomaly spike

Done when:

- repeated null/missing fields create a `NULL_SPIKE` alert
- abnormal event count creates an `ANOMALY_SPIKE` alert
- metric windows are persisted and queryable

## Round 6: Source Health

Goal:

- Turn raw alerts into source-level observability.

Build:

- `source_health` table
- health score calculation
- source freshness detector
- stale source scenario
- `GET /sources/health`
- `GET /sources/{source}/health`

Suggested health score:

```text
100
- duplicate_rate penalty
- late_event_rate penalty
- null_rate penalty
- stale_source penalty
- recent_alert penalty
```

Done when:

- source health scores update after events
- stale source scenario changes source status
- dashboard can show healthy/stale/unhealthy sources

## Round 7: Dashboard

Goal:

- Make the project understandable in 30 seconds.

Build:

- overview page
- alerts page
- source health page
- schema registry page
- demo scenarios page

Done when:

- a user can run a demo scenario from the dashboard
- alert and source health changes are visible without using curl
- screenshots can be added to README

## Round 8: Integration Tests and CI

Goal:

- Make the repo look professional and reliable.

Build:

- Testcontainers tests for PostgreSQL
- Testcontainers tests for Kafka
- detector unit tests
- API integration tests
- GitHub Actions workflow

Minimum tests:

- event validation
- payload hashing
- duplicate detection
- late event detection
- schema inference
- schema drift alert
- null spike alert
- source health score
- demo scenario API
- Kafka-to-PostgreSQL flow

Done when:

- `mvn test` or `gradle test` passes
- CI runs automatically on GitHub

## Round 9: Portfolio Polish

Goal:

- Make the project impressive to recruiters and interviewers.

Build:

- complete README
- architecture diagram
- dashboard screenshots
- demo GIF if possible
- sample event JSON files
- sample incident report
- GitHub repo description and topics
- first release

README must include:

- what the project does
- why it exists
- architecture
- tech stack
- quick start
- demo scenarios
- API examples
- testing instructions
- future roadmap

Done when:

- a stranger can understand the project from the GitHub page without cloning it
- a technical interviewer can see that it is a real Java data engineering project

## 12. AI/Vibecoding Build Instruction

When giving this project to an AI coding tool, use this instruction:

> Build `DriftWatch Tower`, a Java 21 Spring Boot streaming data quality observability platform. Implement it in rounds. Start with a working Spring Boot project, PostgreSQL persistence, and a canonical `DataEvent` model. Then add Kafka ingestion, Kafka Streams quality processors, duplicate and late event detectors, schema drift detection, null spike detection, anomaly spike detection, source health scoring, REST APIs, a simple dashboard, Docker Compose, Testcontainers tests, GitHub Actions, and a polished README. Keep the project local-first and demoable. Do not build auth, payments, cloud deployment, or a large frontend before the backend quality pipeline works.

Important implementation rules:

- Keep every round runnable.
- Prefer clear service boundaries over clever abstractions.
- Store alert evidence as structured JSON.
- Add tests as each detector is implemented.
- Keep demo scenarios deterministic.
- Make the README and dashboard explain the data quality story clearly.

## 13. Acceptance Criteria for the Complete Project

The project is complete when all of these are true:

- `docker compose up` starts Kafka, PostgreSQL, and the app.
- `/actuator/health` returns healthy.
- `/demo/run-scenario/schema-drift` creates a schema drift alert.
- `/demo/run-scenario/duplicate-events` creates a duplicate event alert.
- `/demo/run-scenario/late-events` creates a late event alert.
- `/demo/run-scenario/null-spike` creates a null spike alert.
- `/demo/run-scenario/stale-source` changes a source health status.
- Dashboard shows overview, alerts, source health, schemas, and demo controls.
- PostgreSQL stores events, alerts, schemas, metrics, and source health.
- Tests cover detectors and at least one Kafka/PostgreSQL integration flow.
- README has screenshots, architecture diagram, quick start, and demo commands.
- GitHub repo has description, topics, CI, and first release.

## 14. Future Roadmap

After MVP:

- Add Prometheus metrics and Grafana dashboard.
- Add Slack/Discord webhook alert delivery.
- Add configurable quality rules from YAML.
- Add rule versioning.
- Add event replay/backfill mode.
- Add dead-letter topic for invalid events.
- Add comparison between two schema versions.
- Add source reliability trend chart.
- Add optional OpenAI-generated incident summaries.
- Add integration examples for SourceHero AI and AlphaBrief Agent.

## 15. Resume Bullets

Use these after implementation:

- Built `DriftWatch Tower`, a Java/Spring Boot streaming data quality platform using Kafka Streams to detect schema drift, duplicate events, late arrivals, null spikes, stale sources, and anomaly bursts in real time.
- Designed PostgreSQL-backed quality metadata models for raw events, schema versions, window metrics, source health scoring, and alert evidence storage.
- Implemented deterministic demo scenarios, REST APIs, dashboard views, Docker Compose setup, and Testcontainers integration tests for Kafka/PostgreSQL pipeline reliability.

## 16. Portfolio Positioning

Use the three-project portfolio story:

- `SourceHero AI`: AI/RAG data product for cited retrieval over webpages, RSS, PDFs, and notes.
- `AlphaBrief Agent`: financial/news intelligence system for market data, RSS ingestion, spread analysis, and cited briefings.
- `DriftWatch Tower`: Java streaming data quality infrastructure that monitors event-driven pipelines in real time.

Together, these show:

- AI application development
- data ingestion and retrieval
- financial/news intelligence
- Java backend engineering
- Kafka streaming
- data quality and observability
- reliable system design

