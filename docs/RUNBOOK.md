# DriftWatch Tower runbook

Operational procedures for a self-hosted install. Every command takes an explicit `--project` and
`--env-file`; nothing here touches containers or volumes it does not own.

## Install

```bash
git clone https://github.com/JeremyL691/DriftWatch-Tower.git
cd DriftWatch-Tower
scripts/selfhost.sh init --env-file .execution/selfhost.env   # random credentials, mode 0600
scripts/selfhost.sh up   --env-file .execution/selfhost.env
```

`init` generates the PostgreSQL password, the admin password and the ingest token. The file is
created with mode 0600 and is ignored by git. To use the published image instead of building,
set `DWT_APP_IMAGE=ghcr.io/jeremyl691/driftwatch-tower@sha256:<digest>` in the env file; compose
pulls that digest and never rebuilds it.

Check readiness (readiness reflects PostgreSQL, Kafka and the Kafka Streams runtime, so a
container that is up but not processing does not report healthy):

```bash
curl -fsS http://127.0.0.1:18080/actuator/health/readiness
```

## Upgrade

1. Back up first: `scripts/selfhost.sh backup --env-file .execution/selfhost.env --out backup/pre-upgrade.dump`
2. Point `DWT_APP_IMAGE` at the new digest.
3. `docker compose -p driftwatch --env-file .execution/selfhost.env up -d app`

Flyway applies any new migration on start. Migrations V1–V7 are frozen: they are never edited, so
the schema history of an existing install stays consistent. A schema version newer than the
running image is refused, which is why rolling *back* means restoring the backup rather than
starting an older image against the newer schema.

## Backup and restore

```bash
scripts/selfhost.sh backup  --env-file .execution/selfhost.env --out backup/dwt.dump
scripts/selfhost.sh restore --env-file .execution/selfhost.env --dump backup/dwt.dump --target-db driftwatch_restore
```

`restore` always targets a new database; it never overwrites a live one. A verified restore is
part of the release evidence: the row counts of raw events, alerts, schema versions and receipts
must match the source, and no receipt may exist without its raw row.

Kafka is the durable transport, not the system of record: after restoring PostgreSQL, restart the
application and the sink resumes from its committed offsets. Records already persisted are
skipped by their processed receipts.

## Day-to-day checks

```bash
scripts/selfhost.sh status --env-file .execution/selfhost.env
curl -u "$DWT_ADMIN_USERNAME:$DWT_ADMIN_PASSWORD" http://127.0.0.1:18080/api/v1/sources/collectors
curl -u "$DWT_ADMIN_USERNAME:$DWT_ADMIN_PASSWORD" http://127.0.0.1:18080/api/v1/operations/retention
```

`collectors` reports per-source `last_poll_at`, `last_success_at`, `next_poll_at`, upstream lag,
pending outbox rows, open gaps and any backoff. A source that stops appearing is reported as
`LOST` rather than silently stale; a successful poll with no new events is `QUIET`.

### Synthetic detector demos

An admin-only endpoint exercises the detectors with generated events, which is useful for
confirming that alerting works before real traffic arrives:

```bash
curl -u "$DWT_ADMIN_USERNAME:$DWT_ADMIN_PASSWORD" -X POST \
  http://127.0.0.1:18080/api/v1/demo/run-scenario/duplicate
```

Scenarios: `duplicate`, `normal`, `schema-drift`, `late`, `null-spike`, `anomaly-spike`,
`stale-source`, `field-range`. Their events carry `source` values starting with `demo` (for
example `demo-api`), so they stay distinguishable from real source data everywhere. Nothing
collected from the GitHub source is ever synthesised, and the acceptance evidence counts only
rows whose origin is `GITHUB`. Treat those rows as test data: ignore them when reading the
dashboard, and filter them out before archiving an environment.

## Dead letters

```bash
curl -u "$DWT_ADMIN_USERNAME:$DWT_ADMIN_PASSWORD" "http://127.0.0.1:18080/api/v1/dead-letters?recovery_state=OPEN"
curl -u "$DWT_ADMIN_USERNAME:$DWT_ADMIN_PASSWORD" -X POST \
  http://127.0.0.1:18080/api/v1/dead-letters/<id>/replay
```

A dead letter carries its original topic, partition, offset and a stable diagnostic id. Replay
re-enters the pipeline at the stage that failed and returns a new attempt id; it is safe to run
twice, because the sink's processed receipts make the second attempt a no-op.

## Retention

Retention runs daily in batches of at most 1000 rows: raw events and metric windows after 30
days, resolved alerts and incidents and recovered dead letters after 90 days, source inbox
identities after 35 days. Unresolved alerts and incidents, unrecovered dead letters, pending
outbox rows and collector state are never pruned. Run it manually with
`POST /api/v1/operations/retention/run` when you want the counts immediately.

## Troubleshooting

| Symptom | First check |
|---|---|
| readiness stays `DOWN` | `docker compose logs app`; readiness needs PostgreSQL, Kafka and Streams, so check which component is named |
| no new GitHub events | `GET /api/v1/sources/collectors`: `QUIET` with a recent successful poll is normal; `BACKOFF`/`ERROR` names the reason, and a gap row records what could not be seen |
| ingestion answers `503` | the broker did not acknowledge within the timeout; retry with the same `Idempotency-Key` — the identity is reserved, so a retry cannot duplicate the event |
| ingestion answers `409` | the same `Idempotency-Key` was used with different content; use a new key for a new event |
| disk filling up | `GET /api/v1/operations/retention` shows protected counts; raise retention frequency or lower the windows deliberately |
| dashboard shows `Disconnected` | the socket reconnects with backoff and re-queries; check that `/ws` is reachable through your reverse proxy (it must allow WebSocket upgrades) |

## Uninstall

```bash
scripts/selfhost.sh down --env-file .execution/selfhost.env            # keeps volumes
scripts/selfhost.sh down --env-file .execution/selfhost.env --volumes  # removes them
```

Volumes are project-scoped (`<project>_pgdata`, …), so removing one install cannot affect another.
