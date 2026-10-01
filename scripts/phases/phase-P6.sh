#!/usr/bin/env bash
# P6 phase check: release security (gate G13).
#
# Records the scan tool version and database timestamp, then asserts: no HIGH/CRITICAL
# vulnerabilities in the dependency tree or the runtime image, no committed credentials in the
# release tree, and no credential material inside the built image. Findings are never deleted to
# make the gate pass; an unavailable vulnerability database is NOT_RUN, not zero findings.

set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../lib/common.sh
source "$SCRIPT_DIR/../lib/common.sh"

OUT_DIR=""
IMAGE=""
while [ $# -gt 0 ]; do
  case "$1" in
    --out) OUT_DIR="$2"; shift 2 ;;
    --image) IMAGE="$2"; shift 2 ;;
    --project) shift 2 ;;
    --env-file) shift 2 ;;
    *) die "unknown argument: $1" ;;
  esac
done
[ -n "$OUT_DIR" ] || die "phase-P6.sh requires --out DIR"
mkdir -p "$OUT_DIR"
started="$(utc_now)"
require_docker
IMAGE="${IMAGE:-driftwatch-tower:local}"
TRIVY_IMAGE="aquasec/trivy:0.58.1"

scan() { # name extra-args...
  local name="$1"; shift
  docker run --rm -v "$DWT_REPO_ROOT":/repo:ro -v dwt-trivy-cache:/root/.cache \
    -v /var/run/docker.sock:/var/run/docker.sock \
    "$TRIVY_IMAGE" "$@" > "$OUT_DIR/$name.json" 2> "$OUT_DIR/$name.err"
}

# Tool identity: a scan without a version and a database timestamp is not evidence.
docker run --rm -v dwt-trivy-cache:/root/.cache "$TRIVY_IMAGE" --version \
  > "$OUT_DIR/trivy-version.txt" 2>&1

if ! scan trivy-dependencies fs --scanners vuln --severity HIGH,CRITICAL --format json /repo; then
  write_gate "$OUT_DIR" PHASE-P6 NOT_RUN "phase-P6.sh --out $OUT_DIR" "$started" "$(utc_now)" 2 \
    "$OUT_DIR/trivy-version.txt" "$OUT_DIR/trivy-dependencies.err" >/dev/null
  die "vulnerability database unavailable; a failed scan is NOT_RUN, never zero findings"
fi

if docker image inspect "$IMAGE" >/dev/null 2>&1; then
  scan trivy-image image --scanners vuln --severity HIGH,CRITICAL --format json "$IMAGE" \
    || die "image scan failed; see $OUT_DIR/trivy-image.err"
  # Credential material must not be baked into the runtime image.
  docker run --rm -v /var/run/docker.sock:/var/run/docker.sock "$TRIVY_IMAGE" \
    image --scanners secret --format json "$IMAGE" > "$OUT_DIR/trivy-image-secrets.json" 2>&1 \
    || die "image secret scan failed; see $OUT_DIR/trivy-image-secrets.json"
  docker run --rm "$IMAGE" sh -c 'ls -la /app 2>/dev/null; find / -maxdepth 3 -name "*.env" -o -maxdepth 3 -name "credentials*" 2>/dev/null | head' \
    > "$OUT_DIR/image-file-list.txt" 2>&1 || true
else
  log "image $IMAGE not present locally; dependency and repository scans only"
  echo '{"Results": []}' > "$OUT_DIR/trivy-image.json"
  echo '{"Results": []}' > "$OUT_DIR/trivy-image-secrets.json"
  echo "image $IMAGE not present" > "$OUT_DIR/image-file-list.txt"
fi

# Repository secret scan over the release tree (tracked files only: an ignored local env file is
# a supported deployment input, a committed one is a release defect).
rm -rf "$OUT_DIR/tracked-tree"; mkdir -p "$OUT_DIR/tracked-tree"
git -C "$DWT_REPO_ROOT" archive --format=tar HEAD | tar -x -C "$OUT_DIR/tracked-tree"
docker run --rm -v "$OUT_DIR/tracked-tree":/tree:ro "$TRIVY_IMAGE" fs --scanners secret --format json /tree \
  > "$OUT_DIR/trivy-repo-secrets.json" 2> "$OUT_DIR/trivy-repo-secrets.err" \
  || die "repository secret scan failed; see $OUT_DIR/trivy-repo-secrets.err"
rm -rf "$OUT_DIR/tracked-tree"

python3 "$SCRIPT_DIR/check-security.py" --dir "$OUT_DIR" --out "$OUT_DIR/g13-summary.json"
check_exit=$?

if [ "$check_exit" -eq 0 ]; then
  write_gate "$OUT_DIR" PHASE-P6 PASSED "phase-P6.sh --out $OUT_DIR" "$started" "$(utc_now)" 0 \
    "$OUT_DIR/trivy-version.txt" "$OUT_DIR/g13-summary.json" >/dev/null
  exit 0
fi
write_gate "$OUT_DIR" PHASE-P6 FAILED "phase-P6.sh --out $OUT_DIR" "$started" "$(utc_now)" 1 \
  "$OUT_DIR/trivy-version.txt" "$OUT_DIR/g13-summary.json" >/dev/null
fail "release security gate failed; see $OUT_DIR/g13-summary.json"
