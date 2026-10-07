# DriftWatch Tower v1.0.2

DriftWatch Tower is a self-hosted event-quality service for HTTP event streams and the official
GitHub public-events API. It combines Java 21, Spring Boot, Kafka Streams, and PostgreSQL with a
compact operations dashboard.

## Highlights

- Duplicate checks keep repeated event IDs and repeated payloads as distinct findings, including
  when both apply to the same ingestion. Each finding is persisted without collapsing the other.
- Dashboard status badges keep readable text contrast in dark and light themes, including after
  the source-health view refreshes into an unhealthy state.
- Broker-acknowledged ingestion, stable idempotency receipts, transactional sink persistence,
  bounded retries, and durable dead-letter replay keep event handling inspectable.
- The GitHub collector persists its ETag, inbox identities, outbox, and checkpoints so restarts
  resume from stored state. Bootstrap observations remain separate from live-event evaluation.

## Runtime profile

The service evaluates duplicates, missing fields, null spikes, anomalies, late arrivals, range and
format violations, and schema drift. It stores raw events, alerts, incidents, source state, and
metric projections in PostgreSQL. Kafka carries ingestion, processed quality events, and durable
dead letters; the dashboard presents source health, incidents, dead letters, and live metrics.

The default collector reads the public GitHub events API for `apache/kafka`. It follows the
upstream polling interval, respects rate limits and backoff, and records unavailable history as a
gap instead of estimating a missing-event count. The unauthenticated API budget is limited, so
polling defaults to five minutes.

## Install

Requirements: Docker with Compose, 4 CPU cores, 8 GiB of memory recommended, and a free application
port.

```bash
git clone https://github.com/JeremyL691/DriftWatch-Tower.git
cd DriftWatch-Tower
scripts/selfhost.sh init --env-file .execution/selfhost.env
scripts/selfhost.sh up --env-file .execution/selfhost.env
```

For an immutable prebuilt install, set `DWT_APP_IMAGE` in the generated environment file to the
image digest listed in the GitHub release manifest. Readiness is available at
`http://127.0.0.1:18080/actuator/health/readiness`; open the dashboard at
`http://127.0.0.1:18080/dashboard` and sign in with the administrator credentials in the environment
file.

## Performance and compatibility

The candidate passed a continuous 30-minute load profile at 100 offered events per second:
180,000 of 180,000 timed offers were accepted with no timed-window failures. Acknowledgement
latency p95 was 8.1 ms, commit latency p95 was 111.8 ms, and measured consumer lag returned to zero
30.7 seconds after the timed window. These measurements describe a single-node acceptance setup,
not a capacity guarantee.

The browser acceptance matrix covers Chromium at 320, 768, 1024, and 1440 CSS pixels in dark and
light themes. It checks the populated unhealthy-source state after refresh, color contrast,
keyboard focus, console errors, and horizontal overflow. Other browser engines are outside this
matrix.

DriftWatch Tower targets a single host, one Kafka broker, one PostgreSQL instance, and one
application instance. Horizontal scaling is not implemented. The public events API can be delayed,
rate-limited, or unavailable; collector status and source gaps make those conditions visible.
Source-health scoring is conservative for event fields that are absent by design, and `NO_OVERLAP`
gap records can over-report ranges that the upstream API no longer serves. See the project guide
and runbook for the operational details and retention behavior.

## Release files

The GitHub release page is the source of truth for the immutable container digest, deployment
bundle, CycloneDX SBOM, checksums, and sanitized acceptance evidence. Verify downloaded files with
the published checksum list before installation.
