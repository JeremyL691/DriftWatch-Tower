# Sample Incident Report

## Incident

`SCHEMA_DRIFT` and `NULL_SPIKE` triggered for `demo-api` on event type `demo_null_event`.

## Summary

At `2026-05-25T10:05:00Z`, DriftWatch Tower detected that the `ask` field for `demo_null_event` had become null across the active metric window. The same payload pattern also registered as schema drift because the active schema baseline expected `ask` to be a numeric field.

## Evidence Snapshot

- Source: `demo-api`
- Event type: `demo_null_event`
- Triggered alerts:
  - `NULL_SPIKE`
  - `SCHEMA_DRIFT`
- Null spike window:
  - `window_start`: `2026-05-25T10:05:00Z`
  - `window_end`: `2026-05-25T10:06:00Z`
  - `null_count`: `5`
  - `total_count`: `6`
  - `null_rate`: `0.8333`
- Schema drift change:
  - field: `ask`
  - expected: `NUMBER`
  - observed: `NULL`

## Operational Impact

- Source health score dropped due to elevated null rate.
- Dashboard alert feed showed the incident immediately after the demo scenario ran.
- Metric windows preserved the window-level evidence for later analysis.

## Suggested Follow-up

1. Confirm whether upstream intentionally changed the payload contract.
2. If intentional, promote the new schema baseline after validating downstream consumers.
3. If unintentional, roll back the upstream change or patch the producer to restore `ask`.
4. Review whether `NULL` should be treated as a schema-compatible nullable type in a later detector refinement.
