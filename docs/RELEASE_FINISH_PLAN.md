# v1.0.0 release finishing plan

Owner: this Codex chat. Authorization: the user approved implementation, own-PR push/merge and public GitHub/GHCR publication. Old ZCode automation is paused. Hourly Codex heartbeat `driftwatch-v1` continues this plan quietly and must remain active until real delivery.

## Frozen identity and evidence

Read full identity from `.execution/runs/p7-freeze/manifest.json`. Application candidate is `573154b9b7db0cb78a6db1ad10bd09c8df12fe57`. Do not change application roots, configuration or dependencies during acceptance. Docs, release scripts and workflows may change. `.execution/finalize/release-context.json` explicitly binds gates and raw evidence; missing future gates mean release is unavailable, not passed.

Discover the live run through state, PID, process creation time, command, sample freshness, compose project and image. Never select a run or report by directory modification time. Preserve all failed runs. Historical run at takeover: `20261001T182403Z-soak24`, project `dwt-soak`, env `.execution/soak.env`, PID5002, start18:24:03Z, planned86400s. Planned faults +7200 app-restart, +28800 kafka-stop, +57600 db-stop. Compare same-run poller snapshots before/after each fault (bootstrap count, ETags, LIVE IDs, pending queue, failures).

A lightweight read-only `scripts/soak-finish-watch.py` process is registered in `.execution/finalize/finish-watch.pid.json`. It waits for the existing runner and invokes formal reporting immediately after completion to prove zero outbox/DLT/lag within ten minutes. It never starts/stops services or publishes. Preserve its attempt reports; heartbeat checks the canonical G16 result before cleanup. Do not launch duplicate watchers. Historical watcher PID39749 was stopped after the host-sleep failure on Oct2; verify process identity rather than assuming it persists.

Run `python3 scripts/soak-observe.py --context .execution/finalize/release-context.json` during the window for validated same-run observations; it appends explicit evidence paths to context.

## Ordered execution

1. Finish release tooling and regression checks during soak. Do not run complete container suites, load or install tests concurrently with soak.
2. After actual completion run `./scripts/verify.sh soak-report --run-id <validated-run> --out .execution/verify/final-soak`. Require full86400s, no unexplained gap>120s, >=20 real IDs and >=1 post-bootstrap LIVE ID, all3 planned faults outage<=60s/readiness<=300s, consistent persistence/ingestion ledger, healthy final hour, outbox/DLT/lag0 within10min, no OOM/unplanned restart/disk exhaustion and guide memory bound. Retain raw samples/checkpoints/faults/reports before deleting only this project's test volumes.
3. Rerun G02 fresh project/volumes on frozen image with --no-build (historical G02 belongs to old application). Generate final bundle using --manifest p7-freeze, image-only Compose with public v1 default/digest override. Rerun G15 on those exact bytes. Improve ingestion checks to require returned ID in raw_events and processed receipt, not just HTTP202 or total row count.
4. Validate context, explicit gates, manifest/source/config/dependency/image identity. G01 remains historical EXPECTED_FAILURE; G03 proves current fixes. Package raw real-input/load/security/browser/soak evidence from explicit allowlist; exclude actual env/auth and redact/scan before upload. Preserve same tar bytes accepted by G15.
5. Update PR #1 description with final behavior, measured results and known limits; push own branch, wait for five CI jobs on exact head. No bypass. Known DLT test isolation race permits one retry with first failure retained. Merge PR; compare exact main SHA against frozen application roots.
6. Dispatch release workflow from main with exact main SHA and manifest jar-content identity. Refuse conflicting existing tags/assets. Verify locked build identity before push. Set newly pushed GHCR package Public through official UI, verify anonymous digest pull. If private, preserve failure then rerun same candidate after visibility setting. Release stays prerelease pending G17.
7. Record published platform/digest/local image mapping and scan actual public digest. Upload G15 bundle, manifest, SBOM, checksums and sanitized evidence. Download explicit assets through unauthenticated HTTP, verify every required hash using fresh empty Docker config. G17 must prove auth boundaries, exact ingestion persistence/idempotency, real GitHub poll/ID through DB/API, Dashboard 320/768/1024/1440 dark/light with WebSocket and actual alert operation, restart/data/checkpoint continuity, security/integrity. Mark synthetic source as acceptance/demo and exclude from real-source counts. Report shared Docker host honestly.
8. Upload final reports/supplementary evidence, update manifest/checksums, anonymously reverify final assets. Promote prerelease to formal only after G17. Update completion records and notes; stop heartbeat and clean only owned temporary resources. Deliver Release URL, image@digest, release SHA, G16/G17 reports, platform/performance, limitations and recovery instructions.

## Failure and evidence rules

Invalid JSON always fails closed. Missing/stale candidate/run/report, changed application tree, incomplete G16, missing/corrupt assets/checksums, HTTP202 without specified DB row, authenticated download claimed anonymous, or credential leakage must prevent publication/completion. Validate these scenarios with focused tooling regressions.

Lost runner with invalid continuity: record FAILED and start a new full window after checking ownership. New blocking app defect: repair, refreeze, rerun affected gates and full24h. Tool/docs-only changes: rerun relevant tool checks, preserve valid unchanged-app evidence. Transient network: at most3 backoff attempts. Persistent external failure: exact error and recovery action. Never overwrite unknown public tag/assets; application repair after publication requires a new version.

## Declared v1 limitations

Keep the accepted candidate. Disclose NO_OVERLAP gap overclassification, nullable/inapplicable-field health penalties, stale threshold tuning and first-seen historical event lateness. They are deferred defects/policy limitations; upstream GitHub public-feed latency is a separate upstream constraint. No ZCode product repair in this repository.

## Tool verification at takeover

27 focused release-tool regressions passed locally; shell/Python/Node syntax and frozen source/config/dependency hashes verified. G17 live runtime and browser-action checks are implemented but not yet executed against public artifacts. Packaging/Compose/runtime tests must still run after soak; no completion claim follows from these local tool regressions.

Before upload, record exact main SHA, public digest and architecture/content mapping in context. Use `scripts/image-content.py --context ... --image image@digest --out <mapping.json>` and public digest G13 scan; bind resulting reports explicitly. `upload-release-assets.sh` now requires `--context FILE --artifacts DIR --evidence <exact-tar-file>`, not an evidence directory. It checks the G15 bundle hash and sanitized archive sidecar, refuses conflicting existing assets, and records explicit attachment hashes. Final metadata changes require `--update-metadata`, which only replaces known previous manifest/checksum hashes; unknown conflicts remain failures.

## Oct2 host-sleep recovery

Run `20261001T182403Z-soak24` FAILED with seven UTC gaps over120s, maximum3646s. The macOS monotonic clock excluded suspend; reporting now validates both clocks and the runner fails immediately on a new gap. Thirty focused regressions passed. Preserve the failed formal report and raw evidence; never reuse its duration/events/faults for the replacement. Restart on a fresh project/volumes with the same frozen image/configuration and verify successful real polling before starting. Mac must stay awake with lid open and connected; caffeinate assertions cannot override lid sleep. Completion and release dates move with the new actual start.

Replacement active run: `20261002T200503Z-soak24-recovery`, runner PID73766, project `dwt-soak-recovery`, env `.execution/soak-recovery.env`, port18088, start2026-10-02T20:05:03Z; planned finish2026-10-03T20:05:03Z. Watcher PID73776 is registered dynamically. Initial fresh-volume real bootstrap99, pending0, failures0, READY. Do not use historical observer paths or original fault schedule for this replacement.
