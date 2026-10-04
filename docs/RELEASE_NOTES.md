# DriftWatch Tower v1.0.0

Single-node, self-hosted data-quality inspection for event streams and the official GitHub
public-events API. Java 21, Spring Boot, Kafka Streams, PostgreSQL.

## Artifacts

| Item | Value |
|---|---|
| Release tag | `v1.0.0` |
| Candidate commit | `573154b9b7db0cb78a6db1ad10bd09c8df12fe57` (application surface; frozen and gated) |
| Source tree hash | recorded in `release-manifest.json` (`source_tree_hash`) |
| Config hash | recorded in `release-manifest.json` (`config_hash`) |
| Application content identity | `1070909c890c03cb64031949ff500d1b107fae53147e82c5d8afd6016e7d4c8d` (`content_identity.jar_content_hash`) |
| Image | `ghcr.io/jeremyl691/driftwatch-tower@<digest>` — the digest is recorded in the release body and in `release-manifest.json` (`image.published_digest`) |
| Image tags | `v1.0.0` and `sha-<first 12 hex of the candidate SHA>` |
| SBOM | `driftwatch-tower-v1.0.0.cdx.json` (CycloneDX) |
| Checksums | `checksums.txt` over every attached asset |
| Deployment bundle | `driftwatch-tower-v1.0.0-bundle.tar.gz` (compose, `.env.example`, self-host tooling, runbook, guide) |
| Acceptance evidence | `driftwatch-tower-<run>-evidence.tar.gz` (gate results, load and soak reports, browser captures, scan summaries; credentials redacted) |

Publication is pending. The release pipeline uses the accepted locked inputs: the release pipeline rebuilds from the
same locked inputs and refuses to push unless the application content identity inside the image
matches the frozen candidate (`content_identity.jar_content_hash`). That content hash excludes archive timestamps. Local arm64 and published architecture/digest mappings will be recorded separately in the manifest.

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
| Unit + container suite | 174 tests, 0 failures, 0 errors, 0 skipped, against real Kafka and PostgreSQL containers |
| Detection contract | Guide 5.4 matrix, including the two previously missed-alert cases, plus mode, window and identity boundaries |
| Load (100 events/s for 30 minutes) | 180,000/180,000 offers at 100.0/s, 0 failures, acknowledgement p95 4.9 ms (limit 1 s), commit p95 112 ms (limit 5 s), ledger 180,100 accepted = processed = raw, 0 dead letters, consumer lag back to 0 in 30.1 s |
| Browser | 8/8 captures at 320/768/1024/1440 px in dark and light, no console errors, no horizontal overflow, visible focus, live socket connected |
| Security | Trivy 0.58.1, database 2026-10-01: no HIGH/CRITICAL in the dependency tree or the runtime image, no secret in the image or the tracked tree |
| Continuous 24-hour run | PASSED, `20261003T203107Z-soak24-r2`: 86,413.6 s, 2,709 samples, maximum gap 36.2 s; 327 real IDs (129 LIVE), all three planned faults recovered, outbox/DLT/lag zero within 22 s of completion. 13 NO_OVERLAP classifications retain the disclosed limitation. |
| Public installation | G17 NOT_STARTED; release remains pending until public assets and digest pass independent verification. |

Performance conditions: single node, 11 CPU / 19.327 GB RAM / 41 GiB free disk at freeze time on macOS arm64, Docker Desktop, images pinned by digest,
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
- The source health score penalises fields that the event type does not carry. The null-rate input
  takes the maximum `NULL_RATE` metric window of the last hour, and for example `action` is absent
  from every `PushEvent`, so that window is 1.0 and costs the source 25 of its 100 points even
  though the alerting path correctly raises no `NULL_SPIKE`. A self-hoster may therefore see the
  GitHub source scored as unhealthy while nothing is wrong; the raw values are visible on
  `GET /api/v1/sources/health`. A follow-up release aggregates the null rate only over fields the
  schema baseline confirms for that event type.
- `STALE_SOURCE` alerts are frequent for the GitHub source by design of its threshold, not because
  the collector is failing. Freshness defaults to five minutes, and the public events API publishes
  in bursts that can lag by minutes to hours, so the source legitimately moves healthy → STALE
  whenever nothing new has been observed in that window; each alert marks one such transition
  (polling itself keeps succeeding, and the alert carries `WARN`). A follow-up release tunes the
  threshold for delayed polled sources.
- Retention removes raw payloads after 30 days while keeping the deduplication identity longer.
- `LATE_EVENT` compares `received_at` against the event's own timestamp with a five-minute threshold
  (guide 5.2), and the public events feed can surface an event for the first time long after it was
  created. During the acceptance run one event created `2026-09-30T13:29:32Z` was first received
  `2026-10-01T18:45:32Z` (lateness 105,360 s); because non-INFO alerts open an incident per source
  and event type (guide 7.2), that single event opened one incident. Read these as "the feed made
  this visible late", not as collector delay — the ingestion ledger, deduplication and replay are
  unaffected. A follow-up release classifies first-seen-but-old events separately from delayed
  delivery.
- The dashboard is verified on Chromium at four widths (320/768/1024/1440 px) in two themes; other
  engines are not part of the acceptance matrix. The timeline and tables wrap long unbreakable
  tokens (`overflow-wrap: anywhere` with `min-width: 0`), which is what lets the 320 px layout fit
  when real event text is present; the capture gate asserts `scrollWidth == clientWidth` on every
  capture, and the CI browser job exercises the same check on Linux, where fallback fonts are wider.
- Kafka Streams state is single-instance; a future multi-instance deployment would need
  repartitioning decisions that this release deliberately does not make.
