# v1.0.1 development and release execution plan

This schedule executes the existing P0-P7 tasks. [PROJECT_EXECUTION_GUIDE.md](PROJECT_EXECUTION_GUIDE.md), especially sections 8, 11 and 12, remains the acceptance specification. [EXECUTION_STATE.md](EXECUTION_STATE.md) records actual progress; `.execution/finalize/release-context.json` binds candidate identity and evidence. Completion requires a formal public v1.0.1 Release, anonymous installation of its exact image and assets, and a passing G17 report.

## Current checkpoint (2026-10-04 21:52 PDT)

Actual recovery supplement and all G00/G02-G13 gates passed on the unchanged frozen application. The same scheduler PID9563 was identity-checked and resumed at2026-10-05T04:43:37Z, and is executing fresh-volume G14 at100 offered events/s for1,800 continuous seconds, started2026-10-05T04:50:13Z. The new image's protected Dashboard operations and all eight captures passed. No new soak exists yet. After load/package pass, use the actual new run and its schedule from context. Earlier hold records below describe history; do not blindly resume or duplicate an already-running process.

## Starting evidence

- Branch `codex/csrf-release-v1-0-1`, frozen application commit `e3166d3269ac1ea919c0741cea8bcd5aa277af4e`, including the CSRF repair and validated test-context closure. PR #3 is OPEN/DRAFT; all five CI jobs passed on that exact head, freshly checked in `.execution/verify/v101-recovery-drills/pr3-live.json`. A later head requires fresh CI.
- Public v1.0.0 remains an immutable prerelease after its actual G17 Dashboard operations failed. Preserve the tag, public digest, accepted bundle and failure evidence.
- The first v1.0.1 attempt passed Compose and P2/P3/P4/P5 but failed P5b. Preserve `.execution/verify/v101-attempt1/`. A TRUNCATE cleanup subsequently deadlocked and was reverted; preserve `.execution/verify/v101-isolation-deadlock/`.
- The separate real-HTTP test context now closes after its class. Development validation at 2026-10-04 16:42 PDT passed all 176 tests with zero failures, errors or skips. Evidence: `.execution/verify/v101-isolation-validation-r1/`. The real-HTTP CSRF, OpenAPI, idempotency and detection-contract classes actually ran. This dirty-tree validation precedes the clean-commit acceptance gates.
- Approved runtime inspection at 16:58 PDT confirmed three healthy owned `dwt-v101-gates` containers, the matching new candidate image, twenty mounted volumes and four networks without cross-project sharing. Nine historical containers remain stopped. The host has open lid, AC power, sleep assertions and GitHub connectivity. Evidence: `.execution/verify/20261004T235803Z-runtime-ownership/report.json`.
- The freeze manifest exists at `.execution/runs/v101-freeze/manifest.json`, with image `sha256:32cf329151d3da8960dd29f487429baa77bb614372ddbf0ced32420f0e8a6b95`. Fresh Compose/P2/P3/P4 gates passed. PID9563 (created Oct4 16:48:16 PDT) is deliberately held before further gates to supplement actual recovery drills; see `v101-chain-hold.json`. No v1.0.1 24-hour window has started.

Recovery supplement completed: `.execution/verify/v101-recovery-drills/attempt-3/recovery-report.json` PASSED on the frozen candidate, with eighteen raw artifacts verified against their recorded hashes. The prior attempts remain preserved. All 43 release/recovery safety regressions pass. Resume only the existing identity-checked scheduler after committing tooling/docs; no new soak exists until the remaining gates, load and package pass.

## 1. Commit the durable repair (P1.2, P5.2)

The validated `@DirtiesContext(AFTER_CLASS)` change and initial plan/state are committed and pushed at `e3166d3`. Keep the real-HTTP cookie lifecycle regression and invalid/missing-token rejection. Preserve the failed attempts, and require five CI successes on the final head: unit/topology, real integration, migration/retry/dead letter, four-viewport Dashboard, and image/SCA. PR #3 stays unmerged until required local gates and G16 pass.

Any new isolation failure must be diagnosed from raw evidence and corrected without removing the real-HTTP test, weakening assertions or restoring the failed locking cleanup. Container-start retries follow the existing verifier's bounded policy and retain the original failure.

## 2. Rebuild and freeze one candidate (P6.1)

The candidate was rebuilt and frozen from clean commit `e3166d3`. The freeze command is:

```sh
./scripts/verify.sh freeze --out .execution/runs/v101-freeze \
  --image driftwatch-tower:csrf-v101
```

Bind the actual committed SHA, source/config/dependency hashes, image ID, JAR content identity and manifest path in the release context. Verify the manifest exists and matches the current application roots. Local deployment credentials are generated into mode-0600 env files; evidence records paths, never credential values. Source, dependencies, rules, migrations or runtime configuration changes after freezing require refreezing, affected gates and a full new 24-hour window.

## 3. Execute short gates and load (P6.1)

The local orchestrator `.execution/v101-gate-chain.py` writes `.execution/finalize/v101-chain-state.json`. Resume the verified existing scheduler after the recovery supplement passes; do not launch a duplicate. Its state says RUNNING/P4, but the OS state `Ts` and separate hold marker are authoritative for the intentional hold. Before any future launch, archive prior attempts and confirm no live chain or soak runner. Revalidate ownership before removing only owned temporary test resources. Preserve historical soak volumes and reports.

The phase suites prove integration contracts but do not execute every guide-required operating procedure. Before load/G16, run the additional real drills with `scripts/phases/check-recovery.py` against the exact frozen image and a separately built historical image from `082fd84`. Use new project names, ports 18111-18114, fresh volumes and mode-0600 credentials. Preserve each failed attempt and executed tool source. Require:

1. G07: old ingestion stopped, per-partition cutoff and committed offsets recorded with no nonempty backlog; old PostgreSQL dump and stopped Kafka-volume backup; V1-V7 checksums, historical PK/payload/alerts/schema and old topics preserved; bridge begins at the saved cutoff, publishes exactly the controlled backlog, and a repeat preserves logical IDs/counts; old application works after restoring both backups to fresh resources.
2. G06: actual prolonged database outage, acknowledged controlled Kafka input, durable SINK DLT with the same identity, no partial receipt, two real CSRF-protected replays and exactly one raw row/processing receipt. This transport drill is separate from real-source acceptance and does not claim HTTP acknowledgment during a database outage.
3. G11: actual official-source inbox/checkpoint plus controlled event/alert/receipt evidence; custom-format dump restored into a new database on a new volume; equal counts and row-content hashes for the fifteen named evidence tables; candidate starts against that restored database; live retention prunes expired raw rows while preserving unresolved alerts, schema and collector checkpoints; required metrics and evidence secret scan pass.

Store the generated report and raw artifacts under `.execution/verify/v101-recovery-drills/`, bind the successful attempt as explicit release evidence, and retain the report's candidate SHA/image/source and tool hash. A word match for `Flyway`/`migration`, a suite-only gate or historical drill cannot substitute for these results. Tool/docs-only changes retain the unchanged application freeze after verifying the application-root hash and relevant tooling checks.

| Gates | Verification | Explicit output |
|---|---|---|
| G02 | Fresh Compose project/volumes, exact image, readiness and specified ingestion persistence | `.execution/verify/v101-compose/` |
| G03/G04 | `verify.sh phase P2` | `.execution/verify/v101-P2/` |
| G05/G06/G07 | `verify.sh phase P3` | `.execution/verify/v101-P3/` |
| G08/G09 | `verify.sh phase P4` | `.execution/verify/v101-P4/` |
| G10 | `verify.sh phase P5` | `.execution/verify/v101-P5/` |
| G11 | `verify.sh phase P5b` | `.execution/verify/v101-P5b/` |
| G12 | Actual browser, four viewports, dark/light, protected operations and reconnect | `.execution/verify/v101-P5c/` |
| G13 | `phase-P6.sh`, secrets/auth and dependency/image SCA | `.execution/verify/v101-P6/` |
| G00 | `verify.sh unit`, full suite with Docker required and zero skips | `.execution/verify/v101-unit/` |
| G14 | 100 offered events/s for 1,800 continuous seconds, 180,000 offers, latency/resource and ingestion-ID reconciliation | `.execution/verify/v101-load/` |
| G15 | Image-only package, SBOM/checksums and fresh artifact installation | `.execution/verify/v101-package/` |

G01 remains the preserved original expected-failure demonstration; current G03 must prove repaired detection. Every gate must have exit 0, PASSED, raw evidence and the same frozen candidate identity. Failed tests or a host-suspended load stop the sequence. Do not run complete suites/load/install tests concurrently with G16.

## 4. Run and formally close G16 (P6.2)

After pre-soak gates pass, create fresh `dwt-v101-soak` resources on port 18095 with `.execution/v101-soak.env`. Generate one unique run ID and bind it in the context. Verify the PID, creation time, command, lock, project, image and fresh samples; register one finish watcher and matching sleep assertions.

Require all guide criteria: at least 86,400 continuous seconds measured with UTC and monotonic clocks; no unexplained sampling gap over 120 seconds; at least 20 real GitHub IDs and one new post-bootstrap LIVE ID; app/Kafka/database faults at +2/+8/+16 hours with the stated outage/readiness bounds; reconciled ingestion/persistence; a healthy final hour; zero outbox/DLT/lag within ten minutes of completion; no OOM, unexplained restart or disk exhaustion; the specified memory bound. Formal evidence is `.execution/verify/v101-soak/soak-report.json` and its gate.

Keep the Mac open, on AC, online and awake. Reuse the existing heartbeat and notify only on meaningful progress, failure, completion or required user action. An observation timeout does not justify starting a second runner. On actual continuity failure, preserve the failed run and start a full replacement only after verifying its cause and host conditions. The passed v1.0.0 soak cannot certify v1.0.1.

## 5. Bind final artifacts and merge (P7.1)

1. After formal G16 passes, preserve samples, faults, collector/ingestion ledgers, database backup and report before stopping only this run's owned stack.
2. Rerun final G02 on fresh resources and the frozen image. Rebuild the final image-only v1.0.1 bundle and rerun G15 against those exact bytes. Bind final Compose/package paths; retain pre-soak evidence as history.
3. Run `python3 scripts/release-context.py --context .execution/finalize/release-context.json`; require all identity and gate checks to pass.
4. Build sanitized evidence with `scripts/evidence-pack.sh` using the explicit context allowlist. Exclude actual env/auth files and scan before upload.
5. Update PR #3 around the final behavior, measured results and known limits; verify its exact final head and all five CI successes, resolve actionable feedback, then merge normally. Verify merged main matches frozen application roots and record the exact release SHA.

## 6. Publish and independently install v1.0.1 (P7.2, P7.3)

1. Dispatch the release workflow from the exact merged main SHA and frozen JAR content identity. Refuse conflicting tags/assets. Verify build identity before push; the Release remains a prerelease until G17 passes.
2. Verify anonymous GHCR digest pull. Record public architecture and local/public content mapping, then scan the actual public digest.
3. Upload the accepted G15 bundle, manifest, SBOM, checksums and explicitly named sanitized evidence tar. Verify required asset hashes through unauthenticated HTTP and an empty Docker auth configuration.
4. Run public installation:

   ```sh
   ./scripts/verify.sh release --context .execution/finalize/release-context.json \
     --out .execution/verify/v101-final-release --version v1.0.1 --pr 3
   ```

   G17 must prove auth boundaries, exact persistence/idempotency, real GitHub polling through DB/API, actual protected Dashboard operations at 320/768/1024/1440 in both themes, WebSocket/reconnect, restart/checkpoint continuity and security/integrity. Disclose shared local Docker and any amd64 emulation. Exclude synthetic demo events from real-source counts.
5. After G17 passes, upload final reports/supplementary evidence, update known manifest/checksum metadata and anonymously reverify the complete final asset set. Promote v1.0.1 to formal Release and verify its public state.

## 7. Completion evidence and maintenance

Update canonical P0-P7 task/gate status with accepted v1.0.1 identities and evidence paths. Deliver the Release URL, merged/tag SHA, public image@digest, G16/G17 reports, measured performance/platform, installation/upgrade/backup/recovery instructions and known limitations. Retain the disclosed NO_OVERLAP classification, nullable-field health, stale-policy and first-seen-lateness limits. Stop the existing heartbeat only after verified delivery and clean only owned temporary resources after preserving evidence.

The goal remains active until all delivery requirements are proven. A running soak, local browser success, green CI or published prerelease is an intermediate state.
