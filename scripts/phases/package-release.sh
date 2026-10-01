#!/usr/bin/env bash
# Builds the release artifacts for one frozen candidate (used by G15 and, unchanged, by P7.2).
#
# Everything here is derived from the frozen image and the frozen commit; nothing is rebuilt from
# a different input. The bundle carries the deployment surface only (compose file, config sample,
# self-host tooling, guide, checksums, SBOM, manifest) — never a real .env.

set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../lib/common.sh
source "$SCRIPT_DIR/../lib/common.sh"

OUT_DIR=""
IMAGE=""
VERSION="v1.0.0"
MANIFEST=""
while [ $# -gt 0 ]; do
  case "$1" in
    --out) OUT_DIR="$2"; shift 2 ;;
    --image) IMAGE="$2"; shift 2 ;;
    --version) VERSION="$2"; shift 2 ;;
    --manifest) MANIFEST="$2"; shift 2 ;;
    *) die "unknown argument: $1" ;;
  esac
done
[ -n "$OUT_DIR" ] || die "package-release.sh requires --out DIR"
mkdir -p "$OUT_DIR"
require_docker
require_python
IMAGE="${IMAGE:-driftwatch-tower:local}"
TRIVY_IMAGE="aquasec/trivy:0.58.1"

docker image inspect "$IMAGE" >/dev/null 2>&1 || die "image $IMAGE not found; build it before packaging"

# Identity of the artifact that will actually be shipped.
image_id="$(docker image inspect --format '{{.Id}}' "$IMAGE")"
image_created="$(docker image inspect --format '{{.Created}}' "$IMAGE")"
git_sha="$(git -C "$DWT_REPO_ROOT" rev-parse HEAD)"
image_tar="driftwatch-tower-${VERSION}.tar"

log "exporting $IMAGE ($image_id)"
docker save "$IMAGE" -o "$OUT_DIR/$image_tar"
image_tar_sha="$(python3 -c "
import hashlib,sys
d=hashlib.sha256()
with open(sys.argv[1],'rb') as h:
    for c in iter(lambda: h.read(1<<20), b''): d.update(c)
print(d.hexdigest())" "$OUT_DIR/$image_tar")"

log "generating SBOM"
docker run --rm -v /var/run/docker.sock:/var/run/docker.sock "$TRIVY_IMAGE" \
  image --format cyclonedx --output /dev/stdout "$IMAGE" \
  > "$OUT_DIR/driftwatch-tower-${VERSION}.cdx.json" 2> "$OUT_DIR/sbom.err" \
  || die "SBOM generation failed; see $OUT_DIR/sbom.err"
python3 -c "
import json,sys
data=json.load(open(sys.argv[1]))
assert data.get('bomFormat')=='CycloneDX', 'SBOM is not CycloneDX'
print('SBOM components:', len(data.get('components') or []))" "$OUT_DIR/driftwatch-tower-${VERSION}.cdx.json" \
  || die "SBOM is not valid CycloneDX"

# Deployment surface: the same files a self-hoster needs, copied from the frozen tree.
mkdir -p "$OUT_DIR/bundle/scripts/lib" "$OUT_DIR/bundle/docs"
cp "$DWT_REPO_ROOT/docker-compose.yml" "$OUT_DIR/bundle/"
cp "$DWT_REPO_ROOT/.env.example" "$OUT_DIR/bundle/"
cp "$DWT_REPO_ROOT/scripts/selfhost.sh" "$OUT_DIR/bundle/scripts/"
cp "$DWT_REPO_ROOT/scripts/lib/common.sh" "$OUT_DIR/bundle/scripts/lib/"
cp "$DWT_REPO_ROOT/docs/PROJECT_EXECUTION_GUIDE.md" "$OUT_DIR/bundle/docs/"
# The runbook and the version notes carry the install, upgrade, backup/restore and limit
# statements the guide requires a release to publish; an installer who downloads only the
# bundle must get them. Everything the bundled README links locally ships with it, so no
# link resolves only inside the repository, and the licence travels with the artifacts.
for extra in docs/RUNBOOK.md docs/RELEASE_NOTES.md docs/EXECUTION_STATE.md docs/versions.md \
             docs/assets/driftwatch-architecture.svg LICENSE; do
  [ -f "$DWT_REPO_ROOT/$extra" ] || continue
  mkdir -p "$OUT_DIR/bundle/$(dirname "$extra")"
  cp "$DWT_REPO_ROOT/$extra" "$OUT_DIR/bundle/$extra"
done
[ -f "$DWT_REPO_ROOT/README.md" ] && cp "$DWT_REPO_ROOT/README.md" "$OUT_DIR/bundle/"

python3 - "$OUT_DIR" "$VERSION" "$git_sha" "$image_id" "$image_tar" "$image_tar_sha" \
        "$image_created" "${MANIFEST:-}" <<'PY'
import json, os, sys
out_dir, version, git_sha, image_id, image_tar, image_tar_sha, image_created, manifest_path = sys.argv[1:9]
source = json.load(open(manifest_path)) if manifest_path and os.path.exists(manifest_path) else {}
payload = {
    "version": version,
    "tag": version,
    "candidate_commit": git_sha,
    "image": {
        "local_name": "driftwatch-tower:local",
        "id": image_id,
        "created": image_created,
        "exported_tar": image_tar,
        "exported_tar_sha256": image_tar_sha,
        "registry": "ghcr.io/jeremyl691/driftwatch-tower",
        "published_digest": None,  # filled in after the registry push verifies anonymously
    },
    "source_tree_hash": source.get("source_tree_hash"),
    "config_hash": source.get("config_hash"),
    "dependency_lock_hash": source.get("dependency_lock_hash"),
    "sbom": f"driftwatch-tower-{version}.cdx.json",
    "contents": sorted(os.listdir(os.path.join(out_dir, "bundle"))),
}
with open(os.path.join(out_dir, "release-manifest.json"), "w") as handle:
    json.dump(payload, handle, indent=2)
print(json.dumps(payload, indent=2))
PY

# Checksums cover every shipped artifact, generated last so nothing is added afterwards.
( cd "$OUT_DIR" && find . -type f ! -name checksums.txt ! -name '*.log' -print0 \
    | sort -z | xargs -0 shasum -a 256 > checksums.txt )
tar -czf "$OUT_DIR/driftwatch-tower-${VERSION}-bundle.tar.gz" -C "$OUT_DIR/bundle" .
( cd "$OUT_DIR" && shasum -a 256 "driftwatch-tower-${VERSION}-bundle.tar.gz" >> checksums.txt )
log "artifacts in $OUT_DIR"
ls -la "$OUT_DIR"
