#!/usr/bin/env bash
# Builds the shareable evidence pack for a release: the gate results, the load and soak reports,
# the browser captures and the scan summaries, with every credential value from the local env
# files redacted. Nothing is uploaded from here; the pack is an input to the release assets.

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/common.sh
source "$SCRIPT_DIR/lib/common.sh"

OUT_DIR=""
CONTEXT=""
RUN_ID="$(date -u +%Y%m%dT%H%M%SZ)"
while [ $# -gt 0 ]; do
  case "$1" in
    --context) CONTEXT="$2"; shift 2 ;;
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

[ -n "$CONTEXT" ] || die "evidence-pack.sh requires --context FILE"
python3 "$SCRIPT_DIR/release-context.py" --context "$CONTEXT" >/dev/null
python3 - "$SCRIPT_DIR" "$CONTEXT" "$stage" <<'PYCOLLECT'
import sys, json, shutil
import xml.etree.ElementTree as ET
from pathlib import Path
sys.path.insert(0, sys.argv[1])
import importlib.util
spec = importlib.util.spec_from_file_location('release_context', Path(sys.argv[1]) / 'release-context.py')
module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
import evidence_pack
context, gates = module.validate(sys.argv[2])
stage = Path(sys.argv[3])
files = set(evidence_pack.collect_evidence_files(context, gates, sys.argv[2], module.ROOT))
allowed = {'.json', '.jsonl', '.txt', '.md', '.log', '.png', '.svg', '.xml', '.err', '.jar'}
for value in sorted(files):
    source = module.file_path(value)
    if source.suffix not in allowed or any(word in source.name.lower() for word in ['credential', 'auth.json', '.env']):
        raise ValueError(f'unsafe evidence file: {value}')
    if source.suffix == '.xml':
        ET.fromstring(source.read_text())
    elif source.suffix == '.json':
        json.loads(source.read_text())
    elif source.suffix == '.jsonl':
        for line in source.read_text().splitlines():
            if line.strip(): json.loads(line)
    relative = source.relative_to(module.ROOT)
    target = stage / relative
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(source, target)
(stage / 'file-list.json').write_text(json.dumps(sorted(str(p.relative_to(stage)) for p in stage.rglob('*') if p.is_file()), indent=2))
PYCOLLECT

# Redact and scan all explicitly collected files before creating the archive.
python3 "$SCRIPT_DIR/evidence-safety.py" --stage "$stage" --repo "$DWT_REPO_ROOT"

tar -czf "$OUT_DIR/driftwatch-tower-$RUN_ID-evidence.tar.gz" -C "$OUT_DIR" "$RUN_ID"
( cd "$OUT_DIR" && shasum -a 256 "driftwatch-tower-$RUN_ID-evidence.tar.gz" > "driftwatch-tower-$RUN_ID-evidence.tar.gz.sha256" )
log "evidence pack: $OUT_DIR/driftwatch-tower-$RUN_ID-evidence.tar.gz"
du -sh "$stage" "$OUT_DIR/driftwatch-tower-$RUN_ID-evidence.tar.gz"

python3 - "$SCRIPT_DIR" "$CONTEXT" "$OUT_DIR/driftwatch-tower-$RUN_ID-evidence.tar.gz" <<'PYREPORT'
import hashlib,json,sys,importlib.util
from pathlib import Path
scripts=Path(sys.argv[1])
sys.path.insert(0, str(scripts))
spec=importlib.util.spec_from_file_location('release_context',scripts/'release-context.py')
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
import evidence_pack
context,_=module.validate(sys.argv[2]);path=sys.argv[3]
report=evidence_pack.build_evidence_report(context,hashlib.sha256(Path(path).read_bytes()).hexdigest())
json.dump(report,open(path+'.json','w'),indent=2)
PYREPORT
