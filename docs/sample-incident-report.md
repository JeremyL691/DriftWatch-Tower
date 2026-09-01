# Illustrative Alert Triage Note

This example shows how the schema-drift and null-spike evidence can be read together. The timestamps and counts below are sample values, not benchmark or production data.

## Triage case

`SCHEMA_DRIFT` and `NULL_SPIKE` triggered for a generated `demo-null-source-<uuid>` source on event type `demo_null_event`.

## Summary

The scenario first publishes a numeric `ask` value as the active schema baseline, then sends five events with a null `ask`. The first null event registers schema drift. The null-spike alert fires when the third event brings the window to two null values out of three, crossing the configured `0.6` threshold.

## Evidence Snapshot

- Source: `demo-null-source-<uuid>`
- Event type: `demo_null_event`
- Triggered alerts:
  - `NULL_SPIKE`
  - `SCHEMA_DRIFT`
- Null spike window:
  - `null_count`: `2`
  - `total_count`: `3`
  - `null_rate`: about `0.6667`
  - `threshold`: `0.6`
- Schema drift change:
  - field: `ask`
  - expected: `NUMBER`
  - observed: `NULL`

After all six scenario events are projected, the metric-window snapshot reaches five null values out of six total events. That `5/6` state is later than the alert evidence above.

## What to inspect

- The source-health row reflects the projected null rate.
- The dashboard displays the related alerts after the demo events are processed.
- Metric windows preserved the window-level evidence for later analysis.

## Suggested Follow-up

1. Confirm whether upstream intentionally changed the payload contract.
2. If intentional, promote the new schema baseline after validating downstream consumers.
3. If unintentional, roll back the upstream change or patch the producer to restore `ask`.
4. Review whether `NULL` should be treated as a schema-compatible nullable type in a later detector refinement.
