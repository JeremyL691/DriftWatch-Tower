#!/usr/bin/env bash
# Builds the shareable evidence pack for a release: the gate results, the load and soak reports,
# the browser captures and the scan summaries, with every credential value from the local env
# files redacted. Nothing is uploaded from here; the pack is an input to the release assets.

set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/common.sh
source "$SCRIPT_DIR/lib/common.sh"

OUT_DIR=""
RUN_ID="$(date -u +%Y%m%dT%H%M%SZ)"
while [ $# -gt 0 ]; do
  case "$1" in
    --out) OUT_DIR="$2"; shift 2 ;;
    --run-id) RUN_ID="$2"; shift 2 ;;
    *) die "unknown argument: $1" ;;
  esac
done
[ -n "$OUT_DIR" ] || die "evidence-pack.sh requires --out DIR"
mkdir -p "$OUT_DIR"
require_python
OUT_DIR="$(cd "$OUT_DIR" && pwd)"

stage="$OUT_DIR/$RUN_ID"
rm -rf "$stage"; mkdir -p "$stage"

collect() { # destination-relative-path source
  [ -e "$2" ] || return 0
  mkdir -p "$stage/$(dirname "$1")"
  cp -R "$2" "$stage/$1"
}

# Gate results and run manifests. For each gate id the newest gate.json wins, so re-running a
# gate after a fix never leaves the pack pointing at superseded evidence.
python3 - "$DWT_REPO_ROOT" "$stage" <<'PY2'
import glob, json, os, shutil, sys
repo, stage = sys.argv[1], sys.argv[2]
latest = {}
for path in glob.glob(os.path.join(repo, ".execution", "**", "gate.json"), recursive=True):
    try:
        gate = json.load(open(path))
    except Exception:
        continue
    gate_id = gate.get("id")
    if gate_id and (gate_id not in latest or os.path.getmtime(path) > os.path.getmtime(latest[gate_id])):
        latest[gate_id] = path
os.makedirs(os.path.join(stage, "gates"), exist_ok=True)
for gate_id, path in sorted(latest.items()):
    shutil.copy(path, os.path.join(stage, "gates", f"{gate_id}.json"))
    print(f"gate {gate_id}: {os.path.relpath(path, repo)}")
PY2

newest() { # glob
  ls -1dt $1 2>/dev/null | head -1
}

# Evidence directories are taken from the accepted gate record, never from a guessed directory
# name: a gate re-run under a new --out name (p61-browser4 -> p61-browser10, p61-g13e -> p61-g13h,
# p61-package4 -> p61-package-final3) must not leave the pack shipping the superseded run.
gate_dir() { # gate-id evidence-file
  python3 - "$DWT_REPO_ROOT" "$1" "$2" <<'PY'
import glob, json, os, sys
repo, gate_id, evidence = sys.argv[1], sys.argv[2], sys.argv[3]
best = None
for path in glob.glob(os.path.join(repo, ".execution", "**", "gate.json"), recursive=True):
    try:
        gate = json.load(open(path))
    except Exception:
        continue
    if gate.get("id") != gate_id:
        continue
    if best is None or os.path.getmtime(path) > os.path.getmtime(best):
        best = path
if best is None:
    sys.exit(0)
directory = os.path.dirname(best)
if os.path.exists(os.path.join(directory, evidence)):
    print(directory)
PY
}

pick_dir() { # gate-id evidence-file; falls back to the newest match anywhere
  local dir
  dir="$(gate_dir "$1" "$2")"
  if [ -z "$dir" ]; then
    local hit
    hit="$(newest ".execution/verify/*/$2")"
    [ -n "$hit" ] && dir="$(dirname "$hit")"
  fi
  printf '%s' "$dir"
}

collect "freeze-manifest.json" .execution/runs/p61-freeze/manifest.json

load_dir="$(pick_dir LOAD load-report.json)"
[ -n "$load_dir" ] && collect "load/load-report.json" "$load_dir/load-report.json"
collect "load/FAILURE-NOTES.txt" .execution/verify/p61-load/FAILURE-NOTES.txt

security_dir="$(pick_dir PHASE-P6 g13-summary.json)"
if [ -n "$security_dir" ]; then
  collect "security/g13-summary.json" "$security_dir/g13-summary.json"
  collect "security/trivy-version.txt" "$security_dir/trivy-version.txt"
fi

package_dir="$(pick_dir PACKAGE install-summary.json)"
if [ -n "$package_dir" ]; then
  collect "package/install-summary.json" "$package_dir/install-summary.json"
  collect "package/release-manifest.json" "$package_dir/artifacts/release-manifest.json"
fi

browser_dir="$(pick_dir PHASE-P5c after-report.json)"
[ -n "$browser_dir" ] && collect "browser/report.json" "$browser_dir/after-report.json"

# The soak run the accepted report describes. Its run id comes from the report itself, so the
# pack cannot drift to a neighbouring run (for example a failed one kept as history) because of
# directory timestamps.
soak_dir="$(pick_dir SOAK soak-report.json)"
soak_name=""
if [ -n "$soak_dir" ] && [ -f "$soak_dir/soak-report.json" ]; then
  soak_name="$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1])).get("run_id") or "")' "$soak_dir/soak-report.json" 2>/dev/null)"
fi
[ -n "$soak_name" ] || soak_name="$(basename "$(newest '.execution/soak/*/')")"
if [ -n "$soak_name" ] && [ -d ".execution/soak/$soak_name" ]; then
  collect "soak/$soak_name/soak-report.json" "$soak_dir/soak-report.json"
  collect "soak/$soak_name/result.json" ".execution/soak/$soak_name/result.json"
  collect "soak/$soak_name/state.json" ".execution/soak/$soak_name/state.json"
  collect "soak/$soak_name/faults.jsonl" ".execution/soak/$soak_name/faults.jsonl"
  collect "soak/$soak_name/FAILURE-NOTES.txt" ".execution/soak/$soak_name/FAILURE-NOTES.txt"
fi

# Browser screenshots: the four viewports in both themes.
if [ -n "$browser_dir" ]; then
  for image in "$browser_dir"/*.png; do
    [ -f "$image" ] || continue
    collect "browser/$(basename "$image")" "$image"
  done
fi

# Redact every credential value that appears in the collected text.
python3 - "$stage" "$DWT_REPO_ROOT" <<'PY'
import os, re, sys

stage, repo = sys.argv[1], sys.argv[2]
secrets = set()
for name in os.listdir(os.path.join(repo, ".execution")):
    if not name.endswith(".env"):
        continue
    for line in open(os.path.join(repo, ".execution", name)):
        key, _, value = line.strip().partition("=")
        if key in ("DWT_POSTGRES_PASSWORD", "DWT_ADMIN_PASSWORD", "DWT_INGEST_TOKEN") and len(value) >= 8:
            secrets.add(value)

redacted = []
for base, _, files in os.walk(stage):
    for name in files:
        path = os.path.join(base, name)
        if not name.endswith((".json", ".txt", ".md", ".jsonl", ".log", ".yml", ".yaml")):
            continue
        try:
            text = open(path, encoding="utf-8", errors="ignore").read()
        except Exception:
            continue
        original = text
        for secret in secrets:
            text = text.replace(secret, "[redacted]")
        # Belt and braces: catch credential-looking assignments that never passed through the
        # env files (for example a hand-edited local file).
        text = re.sub(r"(DWT_(?:POSTGRES_PASSWORD|ADMIN_PASSWORD|INGEST_TOKEN)=)[^\s\"']+",
                      r"\1[redacted]", text)
        if text != original:
            open(path, "w", encoding="utf-8").write(text)
            redacted.append(os.path.relpath(path, stage))

# A pack that still contains a live credential is not shareable.
leaks = []
for base, _, files in os.walk(stage):
    for name in files:
        path = os.path.join(base, name)
        if not name.endswith((".json", ".txt", ".md", ".jsonl", ".log")):
            continue
        text = open(path, encoding="utf-8", errors="ignore").read()
        for secret in secrets:
            if secret in text:
                leaks.append(os.path.relpath(path, stage))
                break
print(f"redacted {len(redacted)} file(s); leaks remaining: {len(leaks)}")
if leaks:
    print("LEAK:", *leaks, sep="\n  ")
    sys.exit(1)
PY
[ $? -eq 0 ] || fail "evidence pack still contains a credential value"

tar -czf "$OUT_DIR/driftwatch-tower-$RUN_ID-evidence.tar.gz" -C "$OUT_DIR" "$RUN_ID"
( cd "$OUT_DIR" && shasum -a 256 "driftwatch-tower-$RUN_ID-evidence.tar.gz" > "driftwatch-tower-$RUN_ID-evidence.tar.gz.sha256" )
log "evidence pack: $OUT_DIR/driftwatch-tower-$RUN_ID-evidence.tar.gz"
du -sh "$stage" "$OUT_DIR/driftwatch-tower-$RUN_ID-evidence.tar.gz"
