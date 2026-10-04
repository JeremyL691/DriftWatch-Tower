#!/usr/bin/env bash
# P5c phase check: dashboard acceptance (gate G12).
#
# Captures the real dashboard at 320/768/1024/1440 in dark and light against a live stack and
# asserts: every page loads, no console errors, no page-level horizontal overflow, a visible
# focus indicator, and a connected live-update socket. Requires --base/--user/--password for the
# running instance; the stack itself is started by the caller.

set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../lib/common.sh
source "$SCRIPT_DIR/../lib/common.sh"

OUT_DIR=""
BASE="http://127.0.0.1:18080"
ENV_FILE=".execution/selfhost.env"
while [ $# -gt 0 ]; do
  case "$1" in
    --out) OUT_DIR="$2"; shift 2 ;;
    --base) BASE="$2"; shift 2 ;;
    --env-file) ENV_FILE="$2"; shift 2 ;;
    --project) shift 2 ;;
    *) die "unknown argument: $1" ;;
  esac
done
[ -n "$OUT_DIR" ] || die "phase-P5c.sh requires --out DIR"
mkdir -p "$OUT_DIR"
started="$(utc_now)"

require_cmd node
load_env_file "$ENV_FILE"
set -a; source "$ENV_FILE"; set +a

node "$DWT_REPO_ROOT/scripts/p53-capture.mjs" --out "$OUT_DIR" --label after --base "$BASE" \
  --user "$DWT_ADMIN_USERNAME" --password "$DWT_ADMIN_PASSWORD" --themes dark,light \
  > "$OUT_DIR/capture.log" 2>&1
capture_exit=$?

python3 "$SCRIPT_DIR/check-dashboard.py" --report "$OUT_DIR/after-report.json" --out "$OUT_DIR/g12-summary.json"
check_exit=$?

if [ "$capture_exit" -eq 0 ] && [ "$check_exit" -eq 0 ]; then
  write_gate "$OUT_DIR" "PHASE-P5C" PASSED "phase-P5c.sh --out $OUT_DIR" "$started" "$(utc_now)" 0 \
    "$OUT_DIR/after-report.json" "$OUT_DIR/g12-summary.json" >/dev/null
  exit 0
fi
write_gate "$OUT_DIR" "PHASE-P5C" FAILED "phase-P5c.sh --out $OUT_DIR" "$started" "$(utc_now)" 1 \
  "$OUT_DIR/after-report.json" "$OUT_DIR/g12-summary.json" >/dev/null
fail "dashboard gate failed (capture exit=$capture_exit, checks exit=$check_exit); see $OUT_DIR"
