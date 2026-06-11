# DriftWatch Tower — Project Blueprint

> The definitive vision for what DriftWatch Tower should become: a production-grade streaming data quality observability platform.

---

## 1. Project Vision

DriftWatch Tower is a **streaming data quality observability platform** that sits between event producers and downstream consumers, inspecting every event in real time for quality issues and providing actionable evidence.

**The core idea:** Data pipelines break silently. Events arrive late, payloads mutate, fields go null, sources go quiet. DriftWatch Tower catches all of this — before bad data corrupts your dashboards, ML models, or business decisions.

### What DriftWatch Tower Is

- A **Java-native data quality layer** built on Spring Boot and Kafka
- A **real-time event inspector** that runs quality detectors on every incoming event
- A **quality evidence store** that persists structured alert evidence for debugging and auditing
- A **source health monitor** that tracks the reliability and freshness of every data source
- A **schema registry** that tracks payload shape evolution and detects drift
- A **demo-friendly platform** that can be up and running in 60 seconds with Docker Compose

### What DriftWatch Tower Is Not

- Not a general-purpose monitoring system (it focuses on data quality, not infrastructure)
- Not a replacement for Prometheus/Grafana (it complements them)
- Not an ETL tool (it observes, it doesn't transform)
- Not a SaaS product (it's a portfolio/demo project)

---

## 2. Core Capabilities

### 2.1 Event Ingestion

**Current:** Single-event `POST /events` endpoint, Kafka producer, batch endpoint.

**Target:**
- `POST /events` — single event ingestion
- `POST /events/batch` — bulk event ingestion (100+ events per request)
- `POST /events/stream` — streaming ingestion via SSE or gRPC streaming
- Schema validation at ingestion time (reject malformed events before they hit Kafka)
- Event deduplication at the ingestion layer (idempotency key support)
- Dead-letter queue for events that fail validation
- Event replay mode for backfilling historical data

### 2.2 Quality Detectors

**Current:** 6 detectors (Duplicate, Late, Schema Drift, Null Spike, Anomaly Spike, Stale Source).

**Target (Phase 1 — Core):**
- `DuplicateDetector` — event_id and payload hash deduplication
- `LateEventDetector` — configurable latency thresholds per source
- `SchemaDriftDetector` — payload shape divergence from baseline
- `NullSpikeDetector` — missing field rate monitoring
- `AnomalySpikeDetector` — event count and value anomaly detection
- `StaleSourceDetector` — source freshness and availability monitoring

**Target (Phase 2 — Advanced):**
- `FieldRangeDetector` — numeric fields outside expected bounds (e.g., price < 0)
- `FieldFormatDetector` — string fields failing regex patterns (e.g., malformed emails)
- `CardinalitySpikeDetector` — sudden changes in unique value counts
- `CorrelationDetector` — fields that should move together but diverge
- `VolumeRateDetector` — ingestion rate anomalies (too fast / too slow)
- `SequenceDetector` — out-of-order events in ordered streams

**Target (Phase 3 — ML-Assisted):**
- Statistical anomaly detection (Z-score, IQR, moving average)
- Time-series forecasting for expected baselines
- Adaptive thresholds that learn from historical patterns
- Clustering for grouping similar quality issues

### 2.3 Schema Management

**Current:** Schema inference, hash-based versioning, drift detection.

**Target:**
- Full schema registry with version history
- Schema comparison between any two versions
- Schema evolution tracking (when did field X get added? when did type Y change?)
- Schema compatibility checks (backward/forward/total compatibility)
- Schema export (JSON Schema, Avro, Protobuf definitions)
- Schema-based event routing (different handling for different payload shapes)

### 2.4 Source Health

**Current:** Health score calculation, freshness policy, stale source detection.

**Target:**
- Real-time health dashboard per source
- Health score trend over time (historical charts)
- Configurable health policies per source type
- Health-based alerting (notify when score drops below threshold)
- Source dependency mapping (which sources feed which pipelines)
- SLA tracking per source (uptime, freshness, accuracy targets)

### 2.5 Alert Management

**Current:** Alert creation, evidence storage, REST query.

**Target:**
- Alert lifecycle management (open → investigating → resolved → archived)
- Alert correlation (group related alerts into incidents)
- Alert suppression (don't spam — suppress duplicate alerts within a window)
- Alert routing (send to different channels based on severity/source)
- Alert acknowledgment workflow
- Alert resolution with root cause notes
- Alert SLA tracking (time to detect, time to resolve)

### 2.6 Windowed Metrics

**Current:** Metric windows for null rate and event count.

**Target:**
- Configurable window sizes (1min, 5min, 15min, 1h)
- Multiple metric types per window (count, null rate, avg value, min/max)
- Metric aggregation across sources
- Metric comparison across time periods (hour-over-hour, day-over-day)
- Metric export to Prometheus/StatsD

---

## 3. Integration Points

### 3.1 Kafka Integration

**Current:** Spring Kafka producer/consumer, single topic.

**Target:**
- Multiple Kafka topics (raw-events, quality-alerts, schema-events, health-events)
- Kafka Streams for stateful processing (windowed aggregations, joins)
- Exactly-once semantics with Kafka transactions
- Dead-letter topic for poison pills
- Schema registry integration (Confluent or Apicurio)
- Kafka Connect sink connectors (write alerts to external systems)
- Multi-cluster support (dev/staging/prod Kafka clusters)

### 3.2 PostgreSQL Integration

**Current:** Spring Data JPA, Flyway migrations, 5 tables.

**Target:**
- Partitioned tables for time-series data (raw_events by month)
- Read replicas for API queries
- Connection pooling (HikariCP tuning)
- Retention policies (auto-purge old data)
- Materialized views for dashboard aggregations
- Database-level alert rules (triggers for critical thresholds)
- TimescaleDB extension for advanced time-series queries

### 3.3 Notification Integration

**Current:** None.

**Target:**
- Slack webhook integration (alert notifications to Slack channels)
- Discord webhook integration
- Email notifications (SMTP)
- PagerDuty integration (for critical alerts)
- Webhook callbacks (generic HTTP POST to external systems)
- SMS alerts via Twilio (for critical issues)
- Notification preferences per user/team

### 3.4 Dashboard Integration

**Current:** Static HTML dashboard with summary endpoint.

**Target:**
- React/Vue SPA dashboard with real-time updates
- WebSocket/SSE for live event streaming
- Interactive charts (event volume, alert trends, health scores)
- Drill-down from alert to raw event
- Dashboard customization (configurable panels)
- Dark mode support
- Mobile-responsive design

### 3.5 Monitoring Integration

**Current:** Spring Actuator health endpoint.

**Target:**
- Prometheus metrics endpoint (`/actuator/prometheus`)
- Custom metrics: detector命中率, processing latency, queue depth
- Grafana dashboard templates
- Distributed tracing with OpenTelemetry
- Log aggregation with structured JSON logging
- Error tracking integration (Sentry, Bugsnag)

### 3.6 External Data Source Integration

**Current:** Demo scenarios simulate data sources.

**Target:**
- Binance WebSocket integration (real market ticks)
- RSS feed monitoring (real news/article streams)
- GitHub webhook integration (code change events)
- Database CDC (Change Data Capture) integration
- S3/GCS file event integration
- Custom webhook receivers

---

## 4. API Surface

### 4.1 Current REST API

```
POST   /events                    — ingest single event
POST   /events/batch              — ingest batch of events
GET    /events/recent             — list recent events
GET    /alerts                    — list alerts (with filters)
GET    /alerts/{id}               — get alert detail
POST   /alerts/{id}/resolve       — resolve an alert
GET    /schemas                   — list schema versions
GET    /schemas/{eventType}       — get schema for event type
GET    /metrics/windows           — list metric windows
GET    /sources/health            — list source health
GET    /sources/{source}/health   — get source health detail
POST   /demo/run-scenario/{name}  — run demo scenario
GET    /dashboard/api/summary     — dashboard summary
GET    /swagger-ui.html           — API documentation
```

### 4.2 Target API Surface (v2)

**Event Management:**
```
POST   /api/v2/events                    — ingest event (with idempotency key)
POST   /api/v2/events/batch              — bulk ingest (up to 1000)
GET    /api/v2/events                    — list events (with full filtering)
GET    /api/v2/events/{eventId}          — get event detail
DELETE /api/v2/events/{eventId}          — soft-delete event
POST   /api/v2/events/{eventId}/reprocess — reprocess event through detectors
```

**Alert Management:**
```
GET    /api/v2/alerts                    — list alerts (with advanced filters)
GET    /api/v2/alerts/{id}               — get alert with full evidence
POST   /api/v2/alerts/{id}/acknowledge   — acknowledge alert
POST   /api/v2/alerts/{id}/resolve       — resolve with root cause
POST   /api/v2/alerts/{id}/suppress      — suppress similar alerts
GET    /api/v2/alerts/stats              — alert statistics
```

**Schema Registry:**
```
GET    /api/v2/schemas                   — list all schemas
GET    /api/v2/schemas/{eventType}       — get schema versions
GET    /api/v2/schemas/{eventType}/diff  — compare two versions
PUT    /api/v2/schemas/{eventType}/baseline — set active baseline
```

**Source Health:**
```
GET    /api/v2/sources                   — list all sources
GET    /api/v2/sources/{name}            — get source detail
GET    /api/v2/sources/{name}/health/history — health score trend
PUT    /api/v2/sources/{name}/policy     — update freshness policy
```

**Metrics:**
```
GET    /api/v2/metrics/windows           — list metric windows
GET    /api/v2/metrics/aggregate         — aggregated metrics
GET    /api/v2/metrics/trends            — trend analysis
```

**System:**
```
GET    /api/v2/system/health             — system health
GET    /api/v2/system/stats              — system statistics
GET    /api/v2/system/config             — current configuration
POST   /api/v2/system/reprocess          — reprocess all events
```

---

## 5. Data Model Evolution

### 5.1 Current Schema (v1)

```
raw_events          — ingested events with quality status
quality_alerts      — detector outputs with evidence
schema_versions     — payload schema tracking
metric_windows      — windowed quality metrics
source_health       — per-source health scores
```

### 5.2 Target Schema (v2)

**New tables:**
```
alert_incidents     — groups of related alerts
alert_acknowledgments — who acknowledged what and when
alert_subscriptions — who wants to be notified about what
schema_evolution    — field-level change history
source_dependencies — which sources feed which pipelines
quality_rules       — configurable detector thresholds
quality_rule_versions — versioned rule configurations
event_replay_log    — tracking reprocessed events
notification_log    — audit trail for sent notifications
```

**Extended tables:**
```
raw_events          — add: partition_key, replay_id, correlation_id
quality_alerts      — add: incident_id, acknowledged_by, suppressed_until
source_health       — add: health_history (JSONB), sla_targets
metric_windows      — add: aggregation_type, comparison_baseline
```

---

## 6. Performance Targets

### 6.1 Ingestion Throughput

| Metric | Current | Target |
|--------|---------|--------|
| Single event latency | < 50ms | < 10ms |
| Batch ingestion (100 events) | N/A | < 200ms |
| Sustained throughput | ~100 events/sec | 1,000+ events/sec |
| Kafka partition count | 1 | 6+ (configurable) |

### 6.2 Detection Latency

| Metric | Current | Target |
|--------|---------|--------|
| Duplicate detection | < 100ms | < 10ms |
| Late event detection | < 50ms | < 5ms |
| Schema drift detection | < 200ms | < 50ms |
| Null spike detection | < 100ms | < 20ms |
| Anomaly detection | < 300ms | < 100ms |

### 6.3 Storage

| Metric | Current | Target |
|--------|---------|--------|
| Event retention | Unlimited | 30 days (configurable) |
| Alert retention | Unlimited | 90 days |
| Schema retention | Unlimited | Indefinite |
| Storage per 1M events | ~500MB | < 300MB (compression) |

### 6.4 Query Performance

| Metric | Current | Target |
|--------|---------|--------|
| Alert list query | < 500ms | < 50ms |
| Source health query | < 1s | < 100ms |
| Dashboard summary | < 2s | < 200ms |
| Schema history query | < 500ms | < 50ms |

---

## 7. Deployment Architecture

### 7.1 Local Development

```
Docker Compose:
  - PostgreSQL 16 (port 5432)
  - Kafka 3.7 (port 9092)
  - DriftWatch Tower (port 8080)
  - (Optional) Prometheus + Grafana
```

### 7.2 Production-Like (Single Node)

```
Docker Compose:
  - PostgreSQL 16 with persistent volumes
  - Kafka 3.7 with persistent volumes
  - DriftWatch Tower with resource limits
  - Nginx reverse proxy
  - Let's Encrypt TLS
```

### 7.3 Kubernetes (Future)

```
K8s Manifests:
  - Deployment: DriftWatch Tower (2+ replicas)
  - StatefulSet: PostgreSQL
  - StatefulSet: Kafka (or use managed Kafka)
  - ConfigMap: application.yml
  - Secret: database credentials
  - Ingress: TLS termination
  - HPA: auto-scaling based on event throughput
```

---

## 8. Security Considerations

### 8.1 Current State

- No authentication (demo project)
- No authorization
- No encryption at rest
- No audit logging

### 8.2 Target State (Portfolio-Level)

- API key authentication for ingestion endpoints
- Role-based access control (admin, viewer, operator)
- TLS encryption for all connections
- Audit logging for all write operations
- Rate limiting per API key
- Input validation and sanitization
- SQL injection prevention (via JPA)
- Secrets management (environment variables, not hardcoded)

---

## 9. Observability Stack

### 9.1 Current State

- Spring Actuator health endpoint
- SLF4J logging

### 9.2 Target State

**Metrics:**
- Prometheus-compatible metrics endpoint
- Custom metrics: events.ingested, alerts.fired, detection.latency, source.health.score
- JVM metrics (heap, GC, threads)
- Kafka consumer lag metrics
- PostgreSQL connection pool metrics

**Logging:**
- Structured JSON logging (logback + logstash encoder)
- Correlation IDs for request tracing
- Log levels configurable at runtime
- Audit log for security events

**Tracing:**
- OpenTelemetry integration
- Distributed trace context propagation
- Span creation for key operations (ingest, detect, persist)
- Trace export to Jaeger/Zipkin

**Dashboards:**
- Grafana dashboard templates
- Pre-built panels for: event throughput, alert rates, detection latency, source health
- Alert rules in Grafana for operational thresholds

---

## 10. Testing Strategy

### 10.1 Current State

- Unit tests for detectors, hashing, validation
- Testcontainers integration tests for Kafka + PostgreSQL
- GitHub Actions CI

### 10.2 Target State

**Unit Tests:**
- All detectors with edge cases
- Health score calculation
- Schema inference and diff
- Metric windowing logic
- Alert correlation logic
- Configuration validation

**Integration Tests:**
- Full event flow: API → Kafka → Detection → PostgreSQL
- Schema drift detection end-to-end
- Source health update flow
- Alert lifecycle (create → acknowledge → resolve)
- Batch ingestion flow
- Concurrent ingestion stress test

**Performance Tests:**
- JMH benchmarks for hot paths (payload hashing, schema inference)
- Load testing with Gatling (1000 events/sec sustained)
- Memory profiling under sustained load
- Kafka consumer lag under load

**Contract Tests:**
- API contract tests (ensure API doesn't break)
- Kafka message schema contracts
- Database migration compatibility tests

---

## 11. Documentation Plan

### 11.1 Current State

- README with quick start, architecture, demo scenarios
- Project guide with development rounds
- Sample incident report

### 11.2 Target State

**Developer Documentation:**
- Architecture Decision Records (ADRs) for key design choices
- Contributing guide
- Code style guide
- Local development setup
- Testing guide
- Deployment guide

**API Documentation:**
- OpenAPI 3.0 specification (auto-generated via SpringDoc)
- API changelog
- Migration guides between API versions

**Operational Documentation:**
- Runbook for common issues
- Performance tuning guide
- Scaling guide
- Backup and recovery procedures

**Portfolio Documentation:**
- Resume bullet points
- Interview talking points
- Architecture diagram (Mermaid + SVG)
- Demo script for interviews
- Comparison with alternatives (Kafka Streams, Flink, Great Expectations)

---

## 12. Development Roadmap

### Phase 1: Foundation (Weeks 1-2)

- [x] Project skeleton with Spring Boot
- [x] DataEvent model and validation
- [x] Kafka ingestion pipeline
- [x] PostgreSQL persistence with Flyway
- [x] 6 quality detectors
- [x] REST API
- [x] Dashboard summary
- [x] Demo scenarios
- [x] Testcontainers tests
- [x] GitHub Actions CI
- [x] TODO comments for technical debt
- [x] Shared test utilities
- [x] Global exception handler
- [x] Batch event ingestion
- [x] Swagger UI

### Phase 2: Core Enhancements (Weeks 3-4)

- [ ] Alert lifecycle management (acknowledge, resolve)
- [ ] Alert correlation into incidents
- [ ] Configurable detector thresholds via YAML
- [ ] WebSocket/SSE for real-time dashboard updates
- [ ] Prometheus metrics endpoint
- [ ] Structured JSON logging
- [ ] Docker Compose production hardening
- [ ] API versioning (v1 → v2)

### Phase 3: Advanced Features (Weeks 5-8)

- [ ] Kafka Streams integration for stateful processing
- [ ] Schema comparison and evolution tracking
- [ ] Health score trend charts
- [ ] Notification integrations (Slack, email)
- [ ] Field range and format detectors
- [ ] Cardinality spike detector
- [ ] Event replay and reprocessing
- [ ] Dead-letter queue for poison pills
- [ ] Rate limiting and API key auth

### Phase 4: Production Readiness (Weeks 9-12)

- [ ] Kubernetes deployment manifests
- [ ] OpenTelemetry distributed tracing
- [ ] Grafana dashboard templates
- [ ] Load testing with Gatling
- [ ] JMH benchmarks
- [ ] Security hardening (RBAC, TLS, audit logging)
- [ ] Database partitioning and retention policies
- [ ] Performance optimization (connection pooling, caching)
- [ ] Comprehensive operational documentation

### Phase 5: Portfolio Polish (Week 13)

- [ ] Architecture diagrams (updated)
- [ ] Demo GIF/video for README
- [ ] Interview talking points document
- [ ] Comparison analysis (vs. Great Expectations, Kafka Streams, Flink)
- [ ] Resume bullet refinement
- [ ] GitHub release with changelog

---

## 13. Portfolio Positioning

### The Three-Project Story

1. **SourceHero AI** — AI/RAG data product for cited retrieval over webpages, RSS, PDFs, and notes
2. **AlphaBrief Agent** — Financial/news intelligence system for market data, RSS ingestion, spread analysis, and cited briefings
3. **DriftWatch Tower** — Java streaming data quality infrastructure that monitors event-driven pipelines in real time

### What This Shows

| Skill | SourceHero | AlphaBrief | DriftWatch |
|-------|-----------|------------|------------|
| AI/ML | RAG, embeddings | NLP, analysis | — |
| Data Ingestion | RSS, PDF, web | Market data, RSS | Kafka, batch |
| Backend | Python, FastAPI | Python, agents | Java, Spring Boot |
| Data Quality | — | — | Real-time detection |
| Infrastructure | — | — | Kafka, PostgreSQL |
| Observability | — | — | Health, alerts, metrics |

### Interview Talking Points

1. "I built a streaming data quality platform that detects schema drift, duplicates, and anomalies in real time"
2. "Events flow through Kafka, get inspected by 6 detectors, and produce structured evidence for debugging"
3. "Source health scoring tracks freshness and reliability across all data sources"
4. "I chose Kafka Streams over Flink for Java-native simplicity and local-first development"
5. "Testcontainers integration tests prove the Kafka→PostgreSQL pipeline works end-to-end"

---

## 14. Success Criteria

DriftWatch Tower is **complete** when:

- [ ] All 6 core detectors work with demo scenarios
- [ ] Alert lifecycle (create → acknowledge → resolve) is functional
- [ ] Dashboard shows real-time updates via WebSocket
- [ ] Prometheus metrics are available at `/actuator/prometheus`
- [ ] Structured JSON logs are emitted
- [ ] Docker Compose starts the full stack in < 60 seconds
- [ ] `mvn test` passes with 90%+ unit test coverage
- [ ] Load test sustains 1000 events/sec
- [ ] API documentation is auto-generated via Swagger
- [ ] README explains the project in 30 seconds
- [ ] A technical interviewer can understand the architecture from the repo alone

---

## 15. Anti-Patterns to Avoid

- **Don't build auth unless needed** — it's a demo project, not a SaaS
- **Don't add a React SPA before the backend is solid** — the backend IS the project
- **Don't over-engineer with ML** — simple thresholds work for the demo
- **Don't add cloud deployment as a core feature** — local-first is the selling point
- **Don't create a generic monitoring dashboard** — focus on data quality
- **Don't add features that distract from the Java/Kafka story** — stay focused

---

## 16. Appendix: Key Files Reference

| File | Purpose |
|------|---------|
| `src/main/java/com/driftwatch/quality/QualityProcessor.java` | Central detection pipeline |
| `src/main/java/com/driftwatch/quality/QualityDetector.java` | Detector interface |
| `src/main/java/com/driftwatch/event/DataEvent.java` | Core event record |
| `src/main/java/com/driftwatch/source/SourceHealthCalculator.java` | Health scoring algorithm |
| `src/main/java/com/driftwatch/quality/schema/SchemaInferrer.java` | JSON → schema inference |
| `src/main/java/com/driftwatch/api/EventController.java` | Event ingestion API |
| `src/main/java/com/driftwatch/dashboard/DashboardDataController.java` | Dashboard data API |
| `src/test/java/com/driftwatch/support/InMemoryMetricWindowRecorder.java` | Shared test utility |
| `src/main/java/com/driftwatch/api/GlobalExceptionHandler.java` | Global error handling |
| `docker-compose.yml` | Full stack orchestration |
| `pom.xml` | Maven dependencies |
| `docs/PROJECT_BLUEPRINT.md` | This document |
