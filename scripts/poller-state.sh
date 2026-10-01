#!/usr/bin/env bash
# Prints the GitHub poller's state as JSON: what it has observed, where it left its checkpoint
# and whether anything is waiting to be published.
#
# Used twice in the 24-hour acceptance: once before the planned application restart and once
# after it. The two outputs are compared rather than described - a restart must resume from the
# checkpoint (no fresh bootstrap round, etag retained, live rounds continuing, no backlog), so
# the comparison has to be the same query both times.
#
#   scripts/poller-state.sh --project dwt-soak --env-file .execution/soak.env > before.json
#   ... planned restart ...
#   scripts/poller-state.sh --project dwt-soak --env-file .execution/soak.env > after.json

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/common.sh
source "$SCRIPT_DIR/lib/common.sh"

PROJECT=""
ENV_FILE=".execution/selfhost.env"
while [ $# -gt 0 ]; do
  case "$1" in
    --project) PROJECT="$2"; shift 2 ;;
    --env-file) ENV_FILE="$2"; shift 2 ;;
    *) die "unknown argument: $1" ;;
  esac
done
[ -n "$PROJECT" ] || die "poller-state.sh requires --project NAME"
[ -f "$ENV_FILE" ] || die "env file not found: $ENV_FILE"
require_docker

docker compose -p "$PROJECT" --env-file "$ENV_FILE" exec -T postgres sh -c '
psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -tA -c "
select json_build_object(
  '\''polls_total'\'', (select count(*) from source_poll_runs),
  '\''polls_by_mode'\'', (select coalesce(json_object_agg(mode, n), '\''{}'\''::json)
                          from (select mode, count(*) n from source_poll_runs group by mode) t),
  '\''bootstrap_events'\'', (select count(distinct event_id) from raw_events
                             where origin = '\''GITHUB'\'' and mode = '\''BOOTSTRAP'\''),
  '\''live_events'\'', (select count(distinct event_id) from raw_events
                        where origin = '\''GITHUB'\'' and mode = '\''LIVE'\''),
  '\''inbox_rows'\'', (select count(*) from source_inbox),
  '\''outbox_pending'\'', (select count(*) from source_outbox where status = '\''PENDING'\''),
  '\''event_identities'\'', (select coalesce(json_agg(row_to_json(t)), '\''[]'\''::json) from (
      select i.github_event_id, i.ingestion_id, i.mode, i.received_at,
             (select count(*) from raw_events r where r.ingestion_id=i.ingestion_id) raw_projections,
             (select count(*) from processed_receipts p where p.ingestion_id=i.ingestion_id) processed_receipts,
             (select count(*) from source_outbox o where o.ingestion_id=i.ingestion_id and o.status='\''SENT'\'') published
        from source_inbox i order by i.id) t),
  '\''gaps'\'', (select count(*) from source_gaps),
  '\''collector_state'\'', (select row_to_json(c) from (
      select source, status, etag_applied, etag_candidate, last_poll_at, last_poll_success,
             last_event_at, next_poll_at, consecutive_failures, observed_from, last_error
        from collector_state) c)
)"
'
