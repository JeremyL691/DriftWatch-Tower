#!/usr/bin/env bash
# Uploads the release assets built for one candidate to an existing GitHub Release.
#
# The image itself is published by .github/workflows/release.yml; this script attaches the
# deployment bundle, the manifest (with the published digest filled in), the SBOM, the checksums
# and the sanitized evidence pack. Checksums are regenerated after the digest is recorded, so a
# downloaded asset can always be verified against the manifest.

set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/common.sh
source "$SCRIPT_DIR/lib/common.sh"

VERSION=""
ARTIFACTS=""
EVIDENCE_DIR=""
DIGEST=""
REPO=""
while [ $# -gt 0 ]; do
  case "$1" in
    --version) VERSION="$2"; shift 2 ;;
    --artifacts) ARTIFACTS="$2"; shift 2 ;;
    --evidence) EVIDENCE_DIR="$2"; shift 2 ;;
    --digest) DIGEST="$2"; shift 2 ;;
    --repo) REPO="$2"; shift 2 ;;
    *) die "unknown argument: $1" ;;
  esac
done
[ -n "$VERSION" ] || die "--version is required"
[ -n "$ARTIFACTS" ] || die "--artifacts DIR is required (output of package-release.sh)"
[ -n "$DIGEST" ] || die "--digest sha256:... is required (the immutable published digest)"
[ -d "$ARTIFACTS" ] || die "artifacts directory not found: $ARTIFACTS"
require_cmd gh
require_python
ARTIFACTS="$(cd "$ARTIFACTS" && pwd)"

case "$DIGEST" in
  sha256:*) ;;
  *) die "digest must look like sha256:<64 hex>" ;;
esac

# Record the published digest, then rebuild the checksums over the final artifact set.
python3 - "$ARTIFACTS" "$DIGEST" <<'PY'
import json, os, sys
artifacts, digest = sys.argv[1], sys.argv[2]
path = os.path.join(artifacts, "release-manifest.json")
manifest = json.load(open(path))
manifest["image"]["published_digest"] = digest
manifest["image"]["published_reference"] = manifest["image"]["registry"] + "@" + digest
json.dump(manifest, open(path, "w"), indent=2)
print("release manifest now names", manifest["image"]["published_reference"])
PY
[ $? -eq 0 ] || fail "could not record the digest in release-manifest.json"

# The evidence pack is an attached asset too, so it has to sit inside the checksummed set
# instead of beside it: stage it here, before the checksums are rebuilt.
if [ -n "$EVIDENCE_DIR" ] && [ -d "$EVIDENCE_DIR" ]; then
  evidence_source="$(ls -1t "$EVIDENCE_DIR"/driftwatch-tower-*-evidence.tar.gz 2>/dev/null | head -1)"
  if [ -n "$evidence_source" ]; then
    cp "$evidence_source" "$ARTIFACTS/"
    log "staged $(basename "$evidence_source") for checksumming and upload"
  fi
fi

rm -f "$ARTIFACTS/checksums.txt"
# The bundle is uploaded exactly as package-release.sh built it and package-check.sh (G15)
# verified it: re-tarring here would publish different bytes than the accepted artifact.
bundle_tar="$ARTIFACTS/driftwatch-tower-${VERSION}-bundle.tar.gz"
[ -f "$bundle_tar" ] || die "bundle tarball missing for $VERSION; run verify.sh package first"
( cd "$ARTIFACTS" && find . -type f ! -name checksums.txt ! -name '*.log' ! -name '*.err' -print0 \
    | sort -z | xargs -0 shasum -a 256 > checksums.txt )
( cd "$ARTIFACTS" && shasum -a 256 -c checksums.txt >/dev/null ) || fail "regenerated checksums do not verify"
log "checksums regenerated over $(wc -l < "$ARTIFACTS/checksums.txt") artifacts"

# The SBOM and bundle names are recorded in the manifest, so read them from there rather than
# re-deriving them from the tag: a mismatch between the two would silently drop an asset.
sbom="$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["sbom"])' "$ARTIFACTS/release-manifest.json")"
[ -n "$sbom" ] || die "release-manifest.json does not name an SBOM"
assets=(
  "$ARTIFACTS/release-manifest.json"
  "$ARTIFACTS/checksums.txt"
  "$ARTIFACTS/driftwatch-tower-${VERSION}-bundle.tar.gz"
  "$ARTIFACTS/$sbom"
)
# Every attached asset must be inside checksums.txt, so they all come from the artifacts
# directory (the evidence pack was staged there above).
evidence="$(ls -1t "$ARTIFACTS"/driftwatch-tower-*-evidence.tar.gz 2>/dev/null | head -1)"
[ -n "$evidence" ] && assets+=("$evidence")

for asset in "${assets[@]}"; do
  [ -f "$asset" ] || die "missing asset: $asset"
  # checksums.txt cannot list itself; every other asset must be covered.
  [ "$(basename "$asset")" = "checksums.txt" ] && continue
  grep -q "$(basename "$asset")" "$ARTIFACTS/checksums.txt" \
    || die "asset $(basename "$asset") is not covered by checksums.txt"
done

gh release upload "$VERSION" "${assets[@]}" ${REPO:+--repo "$REPO"} --clobber \
  || fail "uploading release assets failed"
log "uploaded ${#assets[@]} assets to release $VERSION"
gh release view "$VERSION" ${REPO:+--repo "$REPO"} --json assets \
  -q '.assets[].name' | sed 's/^/  asset: /'
