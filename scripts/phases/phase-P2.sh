#!/usr/bin/env bash
# P2 phase check: detection correctness (gate G03).
#
# Runs the unit/container suite with Docker required and asserts that the detection contract
# classes actually ran. The detection matrix itself lives in DetectionContractTest; this script
# fails when those tests are missing, skipped or red.

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../lib/common.sh
source "$SCRIPT_DIR/../lib/common.sh"

OUT_DIR=""
while [ $# -gt 0 ]; do
  case "$1" in
    --out) OUT_DIR="$2"; shift 2 ;;
    --project|--env-file) shift 2 ;;
    *) die "unknown argument: $1" ;;
  esac
done
[ -n "$OUT_DIR" ] || die "phase-P2.sh requires --out DIR"
mkdir -p "$OUT_DIR"
started="$(utc_now)"

require_java_21
require_docker
assert_clean_git

run_suite() {
  ( cd "$DWT_REPO_ROOT" && ./mvnw clean test -Ddwt.requireDocker=true --batch-mode ) \
    > "$OUT_DIR/mvn-phase-p2.log" 2>&1
}
run_suite
exit_code=$?
if [ "$exit_code" -ne 0 ] && grep -qE 'ContainerLaunchException|Wait strategy failed|Container startup failed' "$OUT_DIR/mvn-phase-p2.log"; then
  log "infrastructure failure while starting containers; retrying once"
  mv "$OUT_DIR/mvn-phase-p2.log" "$OUT_DIR/mvn-phase-p2.attempt1.log"
  run_suite
  exit_code=$?
fi

python3 "$SCRIPT_DIR/check-suite.py" \
  --reports target/surefire-reports \
  --out "$OUT_DIR/p2-summary.json" \
  --require com.driftwatch.stream.DetectionContractTest \
  --require com.driftwatch.stream.QualityStreamsTopologyTest \
  --require com.driftwatch.quality.schema.SchemaTransactionIntegrationTest \
  || check_exit=1
check_exit="${check_exit:-0}"

if [ "$exit_code" -eq 0 ] && [ "$check_exit" -eq 0 ]; then
  write_gate "$OUT_DIR" "PHASE-P2" PASSED "phase-P2.sh --out $OUT_DIR" "$started" "$(utc_now)" 0 \
    "$OUT_DIR/p2-summary.json" "$OUT_DIR/mvn-phase-p2.log" >/dev/null
  exit 0
fi
write_gate "$OUT_DIR" "PHASE-P2" FAILED "phase-P2.sh --out $OUT_DIR" "$started" "$(utc_now)" 1 \
  "$OUT_DIR/p2-summary.json" "$OUT_DIR/mvn-phase-p2.log" >/dev/null
fail "P2 gate failed (suite exit=$exit_code, contract check exit=$check_exit); see $OUT_DIR"