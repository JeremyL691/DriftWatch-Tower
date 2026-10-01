# DriftWatch Tower

A single-node data-quality inspection service built with Java 21, Spring Boot, Kafka Streams, and PostgreSQL.

[![CI](https://github.com/JeremyL691/DriftWatch-Tower/actions/workflows/ci.yml/badge.svg)](https://github.com/JeremyL691/DriftWatch-Tower/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue)](LICENSE)

DriftWatch Tower ingests events over HTTP and from the official GitHub public-events API, checks
them against data-quality rules (duplicates, missing fields, null spikes, anomaly spikes, late
arrivals, range/format rules and schema drift), and stores the events, alerts, incidents and
metric projections in PostgreSQL. A static dashboard shows the live state; every mutating action
is auditable and replayable.

## Quick start

Requirements: Docker with Compose (4 CPU / 8 GiB RAM recommended) and a free port for the app.

```bash
git clone https://github.com/JeremyL691/DriftWatch-Tower.git
cd DriftWatch-Tower
scripts/selfhost.sh init --env-file .execution/selfhost.env   # random credentials, mode 0600
scripts/selfhost.sh up   --env-file .execution/selfhost.env
```

The stack is ready when `curl -fsS http://127.0.0.1:18080/actuator/health/readiness` answers
`{"status":"UP"}` (about 10–20 seconds on a warm image cache). Then open
[http://127.0.0.1:18080/dashboard](http://127.0.0.1:18080/dashboard) and sign in with the admin
username and password from the env file.

Send a first event with the ingest token from the same file:

```bash
source .execution/selfhost.env
curl -X POST http://127.0.0.1:18080/api/v1/events \
  -H "Authorization: Bearer $DWT_INGEST_TOKEN" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $(uuidgen)" \
  -d '{"event_id":"demo-1","source":"demo","event_type":"PaymentEvent",
       "event_timestamp":"2026-01-01T00:00:00Z","payload":{"amount":42.5,"currency":"USD"}}'
```

The same request replayed with the same `Idempotency-Key` returns the original identity and
creates no second row.

## What is collected, and how fresh it is

The default source is the official GitHub public-events API for `apache/kafka`, unauthenticated:

| Property | Default | Meaning |
|---|---|---|
| `driftwatch.source.github.poll-interval` | 5 minutes | Delay between poll rounds; the API's `X-Poll-Interval` header is honoured when it asks for more |
| `driftwatch.source.github.max-pages` / `page-size` | 3 × 100 | At most 300 events examined per round |
| `driftwatch.source.github.token` | empty | Optional. Without it the unauthenticated budget is 60 requests/hour, which is why the default interval is five minutes |
| `driftwatch.detector.late.github-threshold` | 8 hours | An event older than this when it arrives is reported as late rather than as a fresh observation |

Boundaries you should expect:

- **This is not realtime.** GitHub's events API itself lags by seconds to hours, and the poller
  only looks every five minutes. The dashboard shows collector state (`last_poll_at`,
  `last_success_at`, `next_poll_at`, upstream lag) so the delay is visible rather than implied.
- **`304 Not Modified` and quiet rounds are normal.** They update poll health and do not move the
  checkpoint.
- **Gaps are recorded, never guessed.** If a poll round is truncated, loses page overlap, or the
  collector is down longer than the API's visible window, a row is written to `source_gaps` with
  the reason; the number of missed events is recorded as unknown rather than invented.
- **Rate limits are respected, not worked around.** `403`/`429` responses back off using
  `Retry-After` or the rate-limit reset, and a budget-exhausted round is recorded as a gap.
- **Bootstrap and live are distinguished.** Events first seen when the collector starts are
  marked `mode=BOOTSTRAP` and do not participate in realtime window evaluation; only later
  arrivals are `mode=LIVE`.
- **Restarts resume.** ETag, inbox identities, outbox state and checkpoints are persisted, so a
  restart continues from the last applied round instead of re-ingesting or skipping.

Retention defaults: raw events and metric windows 30 days, resolved alerts/incidents and
recovered dead letters 90 days, source inbox identities 35 days. Unresolved alerts and
incidents, unrecovered dead letters and pending outbox rows are never pruned.

## Reliability behaviour

- Ingestion is accepted only after the broker acknowledged the record (HTTP `202`). A retry with
  the same `Idempotency-Key` returns the same `ingestion_id`.
- The database sink retries immediately, then at 2 s, 10 s and 30 s. A record that still fails is
  published to `dead-letter-events-v1` with its original topic/partition/offset and a stable
  diagnostic id, and is visible and replayable through the API. Nothing is dropped silently.
- Schema observation and drift alerting run inside the sink transaction under a PostgreSQL
  advisory lock, so a topology thread never touches the database.
- Alert-to-incident correlation, acknowledgement, resolution and source-health staleness are
  idempotent and are covered by tests that assert repeated `GET`s have no side effects.

## Operating

```bash
scripts/selfhost.sh status  --env-file .execution/selfhost.env
scripts/selfhost.sh backup  --env-file .execution/selfhost.env --out backup/dwt.dump
scripts/selfhost.sh restore --env-file .execution/selfhost.env --dump backup/dwt.dump --target-db driftwatch_restore
scripts/selfhost.sh down    --env-file .execution/selfhost.env            # volumes kept
scripts/selfhost.sh down    --env-file .execution/selfhost.env --volumes  # volumes removed
```

Compose projects are namespaced (`<project>_pgdata`, …), so an acceptance run can never read or
delete the volumes of a running install. PostgreSQL and Kafka are never published to the host;
the app binds `127.0.0.1` only. Put your own TLS reverse proxy in front for remote access.

`/actuator/health` is the only anonymous endpoint and returns status only. Metrics, the API
documentation and the dashboard require the admin account; ingestion requires the bearer token.

## Verification

```bash
scripts/verify.sh preflight --out .execution/verify/preflight
scripts/verify.sh unit      --out .execution/verify/unit    # full suite incl. real Kafka/PostgreSQL containers
scripts/verify.sh sca       --out .execution/verify/sca     # Trivy, HIGH/CRITICAL
scripts/verify.sh compose   --project dwt-check --env-file .execution/selfhost.env --out .execution/verify/compose
```

Every command writes a machine-readable `gate.json` plus its raw evidence into `--out`, and exits
`0` (pass), `1` (verification failure) or `2` (external prerequisite missing). Missing Docker or
skipped container tests fail the gate instead of quietly reducing coverage.

The acceptance harness in `scripts/` also drives the browser capture (`p53-capture.mjs`), the
load generator (`loadgen.py`, 100 events/s for 30 minutes with ingestion-ledger reconciliation)
and the resumable 24-hour runner (`acceptance.py`).

## Project documents

- [Architecture](docs/assets/driftwatch-architecture.svg): the deployed topology — the GitHub
  poller with its checkpoints and outbox, the Kafka topics, the Kafka Streams quality topology
  with its detectors and dead-letter branch, PostgreSQL, and the dashboard.
- [Project execution guide](docs/PROJECT_EXECUTION_GUIDE.md): the authoritative product,
  implementation, acceptance and release specification.
  recovery information.
- [Runbook](docs/RUNBOOK.md): install, upgrade, backup/restore, dead letters, retention and
  troubleshooting.
- [Release notes](docs/RELEASE_NOTES.md): artifacts, acceptance results, performance conditions
  and known limits for the current version.
- [Versions](docs/versions.md): pinned images and dependency versions.

## Known limits

- Single node, single Kafka broker, single PostgreSQL instance: this is a self-hosted inspection
  tool, not a horizontally scaled platform.
- The GitHub source depends on a public API that can be delayed, rate-limited or unavailable;
  when that happens the collector records a gap and the dashboard shows the collector as lost
  rather than reporting success.
- Without a GitHub token the collector is limited to 60 requests/hour, which is why the default
  poll interval is five minutes.
- Retention deletes raw payloads after 30 days; the deduplication identity is kept longer than
  the payload so replays remain correct, but old payload contents are not recoverable.
- Browser support is verified on Chromium at 320/768/1024/1440 px in dark and light themes.

## License

[MIT](LICENSE)
