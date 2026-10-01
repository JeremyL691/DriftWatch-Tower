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

python3 - "$OUT_DIR" <<'PY'
import glob, json, sys, xml.etree.ElementTree as ET
out_dir = sys.argv[1]
totals = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0}
required = {"com.driftwatch.stream.DetectionContractTest": None,
            "com.driftwatch.stream.QualityStreamsTopologyTest": None}
for path in sorted(glob.glob('target/surefire-reports/TEST-*.xml')):
    root = ET.parse(path).getroot()
    name = root.get('name')
    counts = {k: int(root.get(k)) for k in ('tests', 'failures', 'errors', 'skipped')}
    for key, value in counts.items():
        totals[key] += value
    if name in required:
        required[name] = counts
json.dump({"totals": totals, "required_classes": required},
          open(f"{out_dir}/p2-summary.json", "w"), indent=2)
print(json.dumps({"totals": totals, "required": required}))
PY

missing=0
for cls in DetectionContractTest QualityStreamsTopologyTest; do
  count="$(python3 -c "
import json,sys
d=json.load(open('$OUT_DIR/p2-summary.json'))['required_classes']
entry=d.get('com.driftwatch.stream.$cls')
print(0 if entry is None else entry['tests']-entry['skipped'])
")"
  [ "$count" -gt 0 ] || { missing=1; log "detection class $cls did not run (count=$count)"; }
done

failures="$(python3 -c "import json;print(json.load(open('$OUT_DIR/p2-summary.json'))['totals']['failures'])")"
errors="$(python3 -c "import json;print(json.load(open('$OUT_DIR/p2-summary.json'))['totals']['errors'])")"
skipped="$(python3 -c "import json;print(json.load(open('$OUT_DIR/p2-summary.json'))['totals']['skipped'])")"

if [ "$exit_code" -eq 0 ] && [ "$missing" = "0" ] && [ "$failures" = "0" ] && [ "$errors" = "0" ] && [ "$skipped" = "0" ]; then
  write_gate "$OUT_DIR" "PHASE-P2" PASSED "phase-P2.sh --out $OUT_DIR" "$started" "$(utc_now)" 0 \
    "$OUT_DIR/p2-summary.json" "$OUT_DIR/mvn-phase-p2.log" >/dev/null
  exit 0
fi
write_gate "$OUT_DIR" "PHASE-P2" FAILED "phase-P2.sh --out $OUT_DIR" "$started" "$(utc_now)" 1 \
  "$OUT_DIR/p2-summary.json" "$OUT_DIR/mvn-phase-p2.log" >/dev/null
fail "P2 detection gate failed (failures=$failures errors=$errors skipped=$skipped missing_class=$missing)"