#!/usr/bin/env bash
# Load gate (G14): 100 events/s for 1800 s against a running stack, with the acknowledgement and
# commit latencies, the ingestion ledger reconciliation and the resource curve all recorded.
#
# The stack must be started by the caller with the load profile; this script never builds or
# starts one, so a load run can never silently compete with another acceptance stack.

set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../lib/common.sh
source "$SCRIPT_DIR/../lib/common.sh"

OUT_DIR=""
PROJECT=""
ENV_FILE=".execution/selfhost.env"
BASE="http://127.0.0.1:18080"
RATE=100
DURATION=1800
while [ $# -gt 0 ]; do
  case "$1" in
    --out) OUT_DIR="$2"; shift 2 ;;
    --project) PROJECT="$2"; shift 2 ;;
    --env-file) ENV_FILE="$2"; shift 2 ;;
    --base) BASE="$2"; shift 2 ;;
    --rate) RATE="$2"; shift 2 ;;
    --duration) DURATION="$2"; shift 2 ;;
    *) die "unknown argument: $1" ;;
  esac
done
[ -n "$OUT_DIR" ] || die "load-check.sh requires --out DIR"
[ -n "$PROJECT" ] || die "load-check.sh requires --project NAME"
mkdir -p "$OUT_DIR"
started="$(utc_now)"

require_python
require_docker
load_env_file "$ENV_FILE"

python3 "$DWT_REPO_ROOT/scripts/loadgen.py" \
  --base "$BASE" --rate "$RATE" --duration "$DURATION" \
  --user "$DWT_ADMIN_USERNAME" --password "$DWT_ADMIN_PASSWORD" \
  --ingest-token "$DWT_INGEST_TOKEN" \
  --project "$PROJECT" --env-file "$ENV_FILE" --out "$OUT_DIR" \
  > "$OUT_DIR/loadgen.log" 2>&1
exit_code=$?
tail -40 "$OUT_DIR/loadgen.log" >&2 || true

if [ "$exit_code" -eq 0 ]; then
  write_gate "$OUT_DIR" LOAD PASSED "load-check.sh --rate $RATE --duration $DURATION --out $OUT_DIR" \
    "$started" "$(utc_now)" 0 "$OUT_DIR/load-report.json" "$OUT_DIR/loadgen.log" >/dev/null
  exit 0
fi
write_gate "$OUT_DIR" LOAD FAILED "load-check.sh --rate $RATE --duration $DURATION --out $OUT_DIR" \
  "$started" "$(utc_now)" "$exit_code" "$OUT_DIR/load-report.json" "$OUT_DIR/loadgen.log" >/dev/null
fail "load gate failed (exit=$exit_code); see $OUT_DIR/load-report.json"
