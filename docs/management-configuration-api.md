# Management configuration API

Admin Basic authentication is required for every route; PUT additionally requires the existing XSRF-TOKEN cookie/X-XSRF-TOKEN header. Ingest tokens cannot read or write settings. All responses/config field names below are snake_case. No credentials, upstream URLs, timeouts or infrastructure fields are accepted or returned.

## Read/save

`GET /api/v1/configuration` and successful `PUT /api/v1/configuration` return:

```json
{
  "saved_revision": 1,
  "effective_revision": null,
  "live_applied_revision": 1,
  "saved": {"...": "complete document below"},
  "effective": {"...": "complete document with this process's effective values"},
  "startup_defaults": {"...": "complete environment/profile document before saved overlay"},
  "pending_restart_fields": ["github_repository"]
}
```

`effective_revision` is the saved revision only when the complete effective document equals the saved document; otherwise null (hybrid snapshots are not falsely called a complete revision). `live_applied_revision` is the latest revision this process committed/applied or loaded at startup. Revision zero is the current process's environment defaults. Defaults are preserved until the first management save. Later restarts adopt the complete saved document **before** detectors, topology settings, metric recorder and single collector initialize. Credentials and official destination remain environment-owned. Returned effective state is process-local, not a cluster-wide propagation claim.

PUT is a full-document replacement, not a patch. Copy `saved` from GET, edit fields and send:

```json
{
  "expected_revision": 0,
  "config": {
    "duplicate_window_ms": 300000,
    "late_threshold_ms": 300000,
    "github_late_threshold_ms": 28800000,
    "null_threshold": 0.6,
    "null_min_samples": 3,
    "anomaly_baseline_windows": 2,
    "anomaly_min_history_windows": 2,
    "anomaly_ratio": 3.0,
    "anomaly_min_count": 5,
    "window_size_ms": 60000,
    "grace_ms": 600000,
    "future_tolerance_ms": 120000,
    "state_retention_ms": 900000,
    "ranges": {"price": {"min": 0.0, "max": 1000000.0}},
    "format_patterns": "sku=^SKU-[0-9]{6}$",
    "github_enabled": false,
    "github_repository": "apache/kafka",
    "github_poll_interval_ms": 300000,
    "github_max_pages": 3,
    "github_page_size": 100
  }
}
```

Numbers must be JSON numbers (integer fields must be integers); boolean must be JSON boolean. All fields are required, including empty `ranges: {}` / `format_patterns: ""` when disabled. Range objects require both `min` and `max` keys; one may be null, not both. Unknown fields, secret fields, coercions, null/missing required fields, malformed regex and non-finite numbers are rejected. Failed saves leave saved/effective state and history untouched. A successful no-op save still creates a revision and an empty changed_fields operation.

- Duration fields: integer milliseconds, 1..2592000000 (30 days). Metric window size must also be >=1000 and a multiple of1000 (the existing metric recorder aligns whole seconds). GitHub poll interval: 300000..86400000 (5 minutes..24 hours).
- Duplicate window >= metric window; GitHub late threshold >= general late threshold.
- State retention >= grace + (anomaly baseline windows + 1) * metric window.
- Null threshold: finite (0,1]; null samples: 1..1000000.
- Anomaly ratio: finite (1,1000000]; count: 1..1000000; baseline: 1..1000. The entire `anomaly_baseline_windows` span must be observed before evaluation; otherwise WARMING_UP. All immediately preceding complete windows in that span contribute to the mean, including real zero windows inside the observed range; unknown earlier history is never fabricated as zero. Defaults2/2 preserve the original two-window behavior. **New PUT requires `anomaly_min_history_windows == anomaly_baseline_windows`**: the minimum field is coupled/redundant, not an independent detection threshold. Existing environment/startup/saved values with `1 <= min_history <= baseline` remain loadable and are returned verbatim (no migration or silent rewrite); a historical lesser value does not reduce the full-span warm-up requirement. The editor must explain that compatibility value, not offer an independently effective minimum control. New saves require explicit coupled correction/confirmation; do not normalize saved values on GET/polling.
- Ranges: <=50 named fields; finite ordered bounds; field names `[A-Za-z_][A-Za-z0-9_.-]{0,127}`. Range and format detectors look up **literal top-level payload keys**, not nested/dotted paths: `price.amount` addresses only a top-level member named exactly `price.amount`, not `payload.price.amount`. Missing such a literal key is skipped. Label the editor accordingly; schema/window leaf-path semantics are separate.
- Format rules: accepts JSON object format `{"field": "regex"}` (supporting quantifiers/regex with commas) or legacy comma-separated `field=regex` grammar, <=8192 chars total, <=50 fields, <=512 chars per compiled Java regex. Operators must use bounded-complexity patterns; length limits do not prove arbitrary Java regex is linear-time.
- GitHub: owner/repository only (owner <=39 alphanumeric/dash, repository <=100 alphanumeric/dash/dot/underscore, whole value <=57 characters so the source id `github:<owner/repository>` fits the 64-character source columns); pages 1..10, page size 1..100. No URL input. GitHub quota/ETag/lease/outbox/backoff behavior is unchanged.

Live **after commit**, without database calls per event: both late thresholds (GitHub sources get their own threshold), null threshold/sample minimum, anomaly ratio/current-count minimum, numeric ranges and precompiled format rules. Each processor invocation uses one coherent threshold snapshot. Existing window fired flags/idempotent delivery state remain: lowering a threshold does not re-evaluate already-processed events or re-fire an already-fired window.

Restart-only: duplicate window; metrics window/grace/future tolerance/retention; anomaly baseline/history span; every GitHub enabled/repository/poll/page setting. These remain effective at startup values until a real restart. Disabling is not an emergency live-stop; pending enabled=false still polls until restart. There is exactly one poller bean, gated on effective enabled. Repository changes retain old durable rows; switching back restores that source identity/checkpoints and normal lease/recovery rules. Drain pending old-source work before switching repositories; settings save does not rewrite identities, checkpoints or receipts.

### Window-state restart safety

Null/anomaly counts, fired flags, their shared watermark and anomaly observation-start keys use `wp1:<SHA-256>|<canonical JSON scope/components>`. The SHA-256 is computed once at startup over `1/window_ms/grace_ms/future_tolerance_ms/retention_ms/baseline_windows/history_windows`. It does **not** include saved revision, live thresholds, pending values, credentials or repository. Baseline version remains part of null field/window keys; externally returned scope/source identities do not change. No Kafka topic/store name, schema baseline store, delivery/dedup identity store, DB receipt/checkpoint/outbox is changed or cleared.

Same-policy restart preserves retained counts/fired flags/history; live-only updates and pending restart edits do not change the namespace. Different policy starts isolated windows/history even when epoch window_start collides. Old policy state is retained only while valid: each policy/scope stores its owning retention and last **accepted live input's processor wall-clock time** in the existing Long watermark store under `retention:<policy-scope>` and `touch:<policy-scope>`. Active window eviction uses that policy's accepted event watermark minus its owning retention, never raw stream time. Independently, a wall-clock sweep once per minute deletes every inactive policy's windows, watermark/lease metadata and observation-start state after owning retention with no accepted live input (up to one extra sweep interval while running). Accepted access also resets an expired lease before reusing counts/history; rejected FUTURE records do not renew it or advance the accepted watermark. Shutdown time counts toward inactivity; resumed restored state is checked at access/clock sweep. Switching back resumes only retained, unexpired policy state; otherwise it warms up. Old unnamespaced window/watermark/observation state is deliberately discarded by initialization/sweeps (one-time detector warm-up on upgrade), never guessed into a policy. Identity/redelivery protection is preserved across all transitions.

Errors: 400 `{ "error": "invalid_configuration", "message": "..." }`; stale expected revision 409 `{ "error": "revision_conflict", "message": "Configuration revision changed; reload before saving" }`; malformed JSON uses existing `invalid_payload`; auth/CSRF 401/403. Do not erase the dirty browser form on error/conflict or periodic refresh. Re-query GET after success.

## History

`GET /api/v1/configuration/history?page=0&size=20` returns a bounded bare array, newest revision first:

```json
[{"revision":1,"actor":"admin","changed_fields":["null_threshold","github_repository"],"result":"SAVED","created_at":"2026-10-11T06:00:00Z"}]
```

Page 0..1000000, size 1..100. Fetch another page when the page is full; an empty/short page ends continuation. Audit stores successful revision, authenticated actor, field **names only**, result and timestamp; no submitted values or rejected payloads. History is not pruned by event retention. No reset endpoint: a user can explicitly save `startup_defaults` with the current revision via the same validated API.

## Supporting alert query

`GET /api/v1/alerts?type=LATE_EVENT&source=github%3Aapache%2Fkafka&status=OPEN&page=0&size=50` composes all supplied filters (AND), still returning the existing bare response array. Status is OPEN/ACKNOWLEDGED/RESOLVED; page 0..1000000 and size 1..100. Ordering is created_at desc, id desc. Existing acknowledge/resolve contracts are unchanged.

Behavior change from earlier releases: `status` no longer overrides `type`/`source`, and an invalid `status` or `type` returns 400 `bad_request` instead of an empty array or 500. A `size` above 100 or a negative `page` also returns 400.

## First-batch supporting reads and actions

- Alerts: GET `/alerts/{id}`; POST `/alerts/{id}/acknowledge` optional `{ "acknowledgedBy": "operator label" }`; POST `/alerts/{id}/resolve` optional `{ "rootCause": "explanation" }`.200 returns existing AlertResponse; missing404; illegal acknowledge409. Response uses existing `acknowledged_by`, `acknowledged_at`, `root_cause`, `resolved_at` spellings; optional mutation **bodies** retain camelCase keys as above. Repeating actions is idempotent. Re-query alert/incident summaries after success.
- Schema compare: GET `/schemas` or `/schemas/{eventType}` returns existing version array `{id,event_type,schema_hash,schema,first_seen_at,last_seen_at,status}`. `schema` is a field-path->type map; compute added/missing/type_changed using the two selected real versions. PUT `/schemas/{eventType}/baseline` with `{ "version_id": 123 }` returns `{event_type,version_id,status,baseline_sync:{state,detail,consumer_application}}`; it targets the exact latest type/version outbox rather than an unrelated global50-row backlog. PUBLISHED proves publication, not consumer application; `consumer_application` verifies whether the local Streams instance has applied the baseline (`APPLIED_LOCAL`) or remains unconfirmed. Re-query **new** GET `/schemas/{eventType}/baseline` for durable current active publication state: `{event_type,version_id,baseline_sync:{state:"PUBLISHED"|"PENDING"|"FAILED"|"UNKNOWN",consumer_application:"APPLIED_LOCAL"|"PENDING_LOCAL"|"UNCONFIRMED"|"UNAVAILABLE",outbox_id,published_at}}`;404 when there is no active version. This endpoint is a pure read and never publishes. Nullable ids/times are explicit.
- Collector: GET `/sources/collectors` remains a bare array for the single currently configured source if a durable state row exists; empty means no persisted poll state, not fabricated success. Existing timing/status/lag/backoff fields plus `etag_applied` boolean remain. Added `etag_candidate` boolean, `pending_poll_run_id`, `observed_from`, `updated_at`, effective `collector_enabled` and `poll_interval_ms`; ETag **values** are not exposed. `pending_outbox` and `open_gaps` now count only that source (old repository rows are not attributed to current source). `recent_gaps` is explicitly bounded to newest20 with `{id,poll_run_id,reason,evidence_level,evidence_note,visible_from,visible_to,confirmed_from,missing_count,recovery_state,created_at,recovered_at}`. `evidence_level` is `SUSPECTED` for heuristic gaps (NO_OVERLAP, DOWNTIME_BEYOND_VISIBLE_RANGE, unknown count) and `RECORDED` for collector-recorded explicit gaps. Do not call recent20 a complete gap history or disabled historical status a running poller. GET `/sources/health` remains the registered source-health array.
- Event drilldown: GET `/events/recent?page=0&size=20` gives existing summaries; GET `/events/{ingestionId}` gives `{ingestion_id,event_id,source,event_type,event_timestamp,received_at,quality_status,origin,mode,payload,baseline_status,window_evaluation,alerts}`.404 if unknown. `alerts` are linked by actual ingestion_id, not business event_id. `window_evaluation.outcome` is independent of quality_status; OK does not mean full participation.
- Retention: GET `/operations/retention` returns existing policy/protected counts; POST `/operations/retention/run` returns `{status:"completed",pruned:{table_name:count,...}}`. Confirm in browser before POST; a failed response is not completion.
- DLT: GET `/dead-letters?stage=SOURCE|STREAM|SINK&status=...&page=0&size=20` gives existing summaries. GET `/dead-letters/{id}` adds reason, scrubbed payload, kafka location and `replay_history` array `{replay_attempt_id,stage,outcome,detail,requested_at}`. POST `/dead-letters/{id}/replay` returns 202 `{replay_attempt_id,stage,outcome,detail,dead_letter_id}` when the replay was published, 503 with the same body when the broker did not acknowledge it (outcome `FAILED`; the attempt is still recorded in `replay_history`), and 404 for an unknown id. Invalid `stage`/`status`/paging values are 400; lists are newest first. Accepted replay/publication is not proof processing recovered; re-query detail/history and actual status. Preserve original ingestion_id. Existing403/404/409/503 paths must remain visible and retryable.

All mutation routes use the existing CSRF boundary; frontend must URL-encode path values, escape server strings, preserve dirty forms on refresh, and re-query successful mutations. No new collector actions, reset endpoint or credentials UI are introduced.
