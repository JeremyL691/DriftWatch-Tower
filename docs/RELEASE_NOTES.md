# DriftWatch Tower v1.0.0

Single-node, self-hosted data-quality inspection for event streams and the official GitHub
public-events API. Java 21, Spring Boot, Kafka Streams, PostgreSQL.

## Artifacts

| Item | Value |
|---|---|
| Release tag | `v1.0.0` |
| Candidate commit | `8a798a6e7396f2a267cc26e1519bf987c7f13ff3` (application surface; frozen and gated) |
| Source tree hash | recorded in `release-manifest.json` (`source_tree_hash`) |
| Config hash | recorded in `release-manifest.json` (`config_hash`) |
| Application content identity | `e574ffcfddbf3e3dd75728b4181b8e5f1eb0f952a36b6e5d6dff86ee424c9d4a` (`content_identity.jar_content_hash`) |
| Image | `ghcr.io/jeremyl691/driftwatch-tower@<digest>` — the digest is recorded in the release body and in `release-manifest.json` (`image.published_digest`) |
| Image tags | `v1.0.0` and `sha-<first 12 hex of the candidate SHA>` |
| SBOM | `driftwatch-tower-v1.0.0.cdx.json` (CycloneDX) |
| Checksums | `checksums.txt` over every attached asset |
| Deployment bundle | `driftwatch-tower-v1.0.0-bundle.tar.gz` (compose, `.env.example`, self-host tooling, runbook, guide) |
| Acceptance evidence | `driftwatch-tower-<run>-evidence.tar.gz` (gate results, load and soak reports, browser captures, scan summaries; credentials redacted) |

The published image is not rebuilt from different inputs: the release pipeline rebuilds from the
same locked inputs and refuses to push unless the application content identity inside the image
matches the frozen candidate (`content_identity.jar_content_hash`). That hash is reproducible —
two independent builds of this commit were verified byte-identical.

## What it does

Ingests events over HTTP (single or batch) and from the official GitHub public-events API for
`apache/kafka`, runs duplicate, missing-field, null-spike, anomaly-spike, late-arrival,
range/format and schema-drift checks in a Kafka Streams topology, persists events, alerts,
incidents and metric projections in PostgreSQL, and serves a static dashboard with live updates.

Delivery is acknowledged only after the broker accepted the record; an `Idempotency-Key` receipt
makes retries return the same identity; the sink de-duplicates inside the persistence
transaction; failures retry four times and then go to a durable dead-letter topic with replay.

## Acceptance results

| Gate | Result |
|---|---|
| Unit + container suite | 171 tests, 0 failures, 0 errors, 0 skipped, against real Kafka and PostgreSQL containers |
| Detection contract | Guide 5.4 matrix, including the two previously missed-alert cases, plus mode, window and identity boundaries |
| Load (100 events/s for 30 minutes) | 180,000/180,000 offers at 100.0/s, 0 failures, acknowledgement p95 5.5 ms (limit 1 s; p99 10.9 ms), commit p95 112 ms (limit 5 s), ledger 180,100 accepted = processed = raw, 0 dead letters, consumer lag back to 0 in 29.3 s |
| Browser | 8/8 captures at 320/768/1024/1440 px in dark and light, no console errors, no horizontal overflow, visible focus, live socket connected |
| Security | Trivy 0.58.1, database 2026-10-01: no HIGH/CRITICAL in the dependency tree or the runtime image, no secret in the image or the tracked tree |
| Continuous 24-hour run | run `20261001T145553Z-soak24` on the frozen image; the numbers land in the evidence pack (`soak/20261001T145553Z-soak24/soak-report.json`): duration and monitor continuity, real GitHub events (distinct, bootstrap, live), the three controlled faults with outage and recovery times, the ingestion ledger, lag, and the memory curve |

Performance conditions: single node, 11 CPU / 18 GiB RAM / 45 GiB free disk at freeze time
(43 GiB when these notes were written) on macOS arm64, Docker Desktop, images pinned by digest,
one Kafka broker and one PostgreSQL instance in the same compose project. The load figures are
measurements on that machine, not a capacity promise.

## Install

```bash
git clone https://github.com/JeremyL691/DriftWatch-Tower.git && cd DriftWatch-Tower
scripts/selfhost.sh init --env-file .execution/selfhost.env
echo "DWT_APP_IMAGE=ghcr.io/jeremyl691/driftwatch-tower@sha256:<digest>" >> .execution/selfhost.env
scripts/selfhost.sh up --env-file .execution/selfhost.env
```

Readiness: `curl -fsS http://127.0.0.1:18080/actuator/health/readiness`. Dashboard:
`http://127.0.0.1:18080/dashboard` with the admin account from the env file.

## Upgrade, backup and restore

See [RUNBOOK.md](RUNBOOK.md). Migrations V1–V7 are frozen; an older image is refused against a
newer schema, so rolling back means restoring the backup. `selfhost.sh restore` always targets a
new database.

## Known limits

- Single node: one Kafka broker, one PostgreSQL instance, one application instance. Horizontal
  scaling is not implemented.
- The GitHub source is a public API that can be delayed, rate-limited or unavailable. Without a
  token the budget is 60 requests/hour, which is why the default poll interval is five minutes.
  Bootstrap backfill is persisted and schema-observed but never treated as realtime signal;
  gaps are recorded with an unknown size rather than estimated.
- Gap records are deliberately conservative, and currently over-report. The collector writes a
  `NO_OVERLAP` gap whenever a live poll finds an event newer than the *start* of its initial
  backfill, which for a busy repository is always; those rows stay open and accumulate. Read them
  as "the public API can no longer serve that range for re-reading", not as "these events were
  lost" — the ingestion ledger, deduplication and replay are unaffected, and the follow-up release
  corrects the classifier. This is visible as `open_gaps` on `GET /api/v1/sources/collectors`.
- `STALE_SOURCE` alerts are frequent for the GitHub source by design of its threshold, not because
  the collector is failing. Freshness defaults to five minutes, and the public events API publishes
  in bursts that can lag by minutes to hours, so the source legitimately moves healthy → STALE
  whenever nothing new has been observed in that window; each alert marks one such transition
  (polling itself keeps succeeding, and the alert carries `WARN`). A follow-up release tunes the
  threshold for delayed polled sources.
- Retention removes raw payloads after 30 days while keeping the deduplication identity longer.
- The dashboard is verified on Chromium at four widths in two themes; other engines are not part
  of the acceptance matrix.
- Kafka Streams state is single-instance; a future multi-instance deployment would need
  repartitioning decisions that this release deliberately does not make.
