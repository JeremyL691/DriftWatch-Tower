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

# Gate results and run manifests.
for gate in .execution/verify/*/gate.json; do
  [ -f "$gate" ] || continue
  name="$(basename "$(dirname "$gate")")"
  collect "gates/$name.json" "$gate"
done
collect "freeze-manifest.json" .execution/runs/p61-freeze/manifest.json
collect "load/load-report.json" .execution/verify/p61-load3/load-report.json
collect "load/load-report-failed-attempt.json" .execution/verify/p61-load/load-report.json
collect "load/FAILURE-NOTES.txt" .execution/verify/p61-load/FAILURE-NOTES.txt
collect "security/g13-summary.json" .execution/verify/p61-g13d/g13-summary.json
collect "security/trivy-version.txt" .execution/verify/p61-g13d/trivy-version.txt
collect "package/install-summary.json" .execution/verify/p61-package3/install-summary.json
collect "package/release-manifest.json" .execution/verify/p61-package3/artifacts/release-manifest.json
collect "browser/report.json" .execution/verify/p61-browser3/after-report.json

# The most recent soak run (report, faults and a bounded sample window).
latest_soak="$(ls -1dt .execution/soak/*/ 2>/dev/null | head -1)"
if [ -n "$latest_soak" ]; then
  soak_name="$(basename "$latest_soak")"
  collect "soak/$soak_name/soak-report.json" "$latest_soak/soak-report.json"
  collect "soak/$soak_name/result.json" "$latest_soak/result.json"
  collect "soak/$soak_name/state.json" "$latest_soak/state.json"
  collect "soak/$soak_name/faults.jsonl" "$latest_soak/faults.jsonl"
  collect "soak/$soak_name/FAILURE-NOTES.txt" "$latest_soak/FAILURE-NOTES.txt"
fi

# Browser screenshots: the four viewports in both themes.
for image in .execution/verify/p61-browser3/*.png; do
  [ -f "$image" ] || continue
  collect "browser/$(basename "$image")" "$image"
done

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
