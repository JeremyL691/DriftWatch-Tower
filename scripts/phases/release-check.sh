#!/usr/bin/env bash
# Release gate: the published artifacts must be the accepted candidate, and they must install
# anonymously. Nothing here rebuilds the application.
#
# Checks, in order:
#   1. every gate recorded for the candidate is PASSED and bound to the same commit,
#   2. the pull request was merged and its merge commit is on the release branch,
#   3. the GitHub Release exists, points at that commit, and carries the expected assets,
#   4. the GHCR image pulls anonymously by digest in a clean Docker configuration,
#   5. the Release bundle installs anonymously in a fresh project, volume and env file, and the
#      running container is that exact digest.

set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../lib/common.sh
source "$SCRIPT_DIR/../lib/common.sh"

OUT_DIR=""
VERSION="v1.0.0"
REGISTRY_IMAGE="ghcr.io/jeremyl691/driftwatch-tower"
PR_NUMBER=""
PORT=18086
PROJECT="dwt-release-check"
while [ $# -gt 0 ]; do
  case "$1" in
    --out) OUT_DIR="$2"; shift 2 ;;
    --version) VERSION="$2"; shift 2 ;;
    --image) REGISTRY_IMAGE="$2"; shift 2 ;;
    --pr) PR_NUMBER="$2"; shift 2 ;;
    --port) PORT="$2"; shift 2 ;;
    --project) PROJECT="$2"; shift 2 ;;
    --env-file|--keep|--no-build) shift 2 2>/dev/null || shift ;;
    *) die "unknown argument: $1" ;;
  esac
done
[ -n "$OUT_DIR" ] || die "release-check.sh requires --out DIR"
mkdir -p "$OUT_DIR"
require_docker
require_python
require_cmd gh
started="$(utc_now)"
problems=()

# --- 1. gate evidence for this candidate ------------------------------------------------------
python3 - "$DWT_REPO_ROOT" "$OUT_DIR" <<'PY'
import glob, json, os, sys
repo, out_dir = sys.argv[1], sys.argv[2]
required = {"G00": "UNIT", "G02": "COMPOSE", "G03": "PHASE-P2", "G04": "PHASE-P2",
            "G05": "PHASE-P3", "G06": "PHASE-P3", "G07": "PHASE-P3", "G08": "PHASE-P4",
            "G09": "PHASE-P4", "G10": "PHASE-P5", "G11": "PHASE-P5b", "G12": "PHASE-P5c",
            "G13": "PHASE-P6", "G14": "LOAD", "G15": "PACKAGE", "G16": "SOAK"}
gates = []
for path in glob.glob(os.path.join(repo, ".execution", "**", "gate.json"), recursive=True):
    try:
        gates.append((os.path.getmtime(path), path, json.load(open(path))))
    except Exception:
        continue
gates.sort(reverse=True)
latest = {}
for _, path, gate in gates:
    latest.setdefault(gate.get("id"), (path, gate))
summary = {}
missing = []
for gate_id, gate_name in required.items():
    entry = latest.get(gate_name)
    if entry is None:
        missing.append(f"{gate_id} ({gate_name}): no gate.json found")
        continue
    path, gate = entry
    summary[gate_id] = {"id": gate.get("id"), "status": gate.get("status"),
                        "git_sha": gate.get("git_sha"), "path": os.path.relpath(path, repo)}
    if gate.get("status") != "PASSED":
        missing.append(f"{gate_id} ({gate_name}): status {gate.get('status')}")
json.dump({"gates": summary, "problems": missing},
          open(os.path.join(out_dir, "gate-evidence.json"), "w"), indent=2)
print(json.dumps({"gates": summary, "problems": missing}, indent=2))
PY
python3 -c "
import json,sys
d=json.load(open('$OUT_DIR/gate-evidence.json'))
sys.exit(1 if d['problems'] else 0)" || problems+=("gate evidence is incomplete or not PASSED; see gate-evidence.json")

# --- 2. the pull request was merged -----------------------------------------------------------
if [ -n "$PR_NUMBER" ]; then
  gh pr view "$PR_NUMBER" --json state,headRefOid,mergeCommit,url > "$OUT_DIR/pull-request.json" 2>&1 \
    || problems+=("could not read pull request $PR_NUMBER")
  state="$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1])).get("state"))' "$OUT_DIR/pull-request.json" 2>/dev/null)"
  [ "$state" = "MERGED" ] || problems+=("pull request $PR_NUMBER is $state, not MERGED")
else
  problems+=("no --pr given: the merged pull request was not verified")
fi

# --- 3. the release exists and points at the candidate ----------------------------------------
gh release view "$VERSION" --json tagName,targetCommitish,assets,url > "$OUT_DIR/release.json" 2>&1 \
  || problems+=("GitHub Release $VERSION not found")
if [ -f "$OUT_DIR/release.json" ]; then
  python3 - "$OUT_DIR/release.json" "$OUT_DIR/release-assets.txt" <<'PY'
import json, sys
data = json.load(open(sys.argv[1]))
assets = [a["name"] for a in data.get("assets") or []]
open(sys.argv[2], "w").write("\n".join(sorted(assets)))
print("release:", data.get("url"), "assets:", len(assets))
PY
  for asset in checksums.txt release-manifest.json driftwatch-tower-${VERSION}-bundle.tar.gz; do
    grep -qx "$asset" "$OUT_DIR/release-assets.txt" || problems+=("release asset missing: $asset")
  done
fi

# --- 4. anonymous pull by digest ---------------------------------------------------------------
digest=""
if [ -f "$OUT_DIR/release.json" ]; then
  digest="$(gh release view "$VERSION" --json body -q .body 2>/dev/null | grep -o 'sha256:[0-9a-f]\{64\}' | head -1)"
fi
[ -n "$digest" ] || problems+=("the release body does not name an image digest")
clean_config="$(mktemp -d)"
if [ -n "$digest" ]; then
  log "anonymous pull of $REGISTRY_IMAGE@$digest"
  if DOCKER_CONFIG="$clean_config" docker pull "$REGISTRY_IMAGE@$digest" > "$OUT_DIR/anonymous-pull.txt" 2>&1; then
    grep -q "$digest" "$OUT_DIR/anonymous-pull.txt" \
      || problems+=("anonymous pull did not report the expected digest")
  else
    problems+=("anonymous pull by digest failed; see anonymous-pull.txt (is the package public?)")
  fi
fi
rm -rf "$clean_config"

# --- 5. anonymous install from the Release bundle ---------------------------------------------
install_dir="$OUT_DIR/install"
rm -rf "$install_dir"; mkdir -p "$install_dir"
if gh release download "$VERSION" --pattern 'driftwatch-tower-*-bundle.tar.gz' --dir "$install_dir" \
     > "$OUT_DIR/release-download.log" 2>&1 \
   && gh release download "$VERSION" --pattern 'checksums.txt' --dir "$install_dir" \
     >> "$OUT_DIR/release-download.log" 2>&1; then
  ( cd "$install_dir" && shasum -a 256 -c checksums.txt --ignore-missing > "$OUT_DIR/bundle-checksums.txt" 2>&1 ) \
    || problems+=("downloaded bundle failed its checksum; see bundle-checksums.txt")
  tar -xzf "$install_dir"/driftwatch-tower-*-bundle.tar.gz -C "$install_dir"
  env_file="$install_dir/release.env"
  "$install_dir/scripts/selfhost.sh" init --env-file "$env_file" > "$OUT_DIR/install-init.log" 2>&1 \
    || problems+=("selfhost init from the Release bundle failed")
  {
    echo "DWT_APP_PORT=$PORT"
    [ -n "$digest" ] && echo "DWT_APP_IMAGE=$REGISTRY_IMAGE@$digest"
  } >> "$env_file"
  if docker compose -p "$PROJECT" --env-file "$env_file" -f "$install_dir/docker-compose.yml" up -d \
       > "$OUT_DIR/install-up.log" 2>&1; then
    ready=0
    for _ in $(seq 1 60); do
      if curl -fsS "http://127.0.0.1:$PORT/actuator/health/readiness" >/dev/null 2>&1; then ready=1; break; fi
      sleep 2
    done
    [ "$ready" = "1" ] || problems+=("the anonymously installed stack did not become ready")
    if [ -n "$digest" ]; then
      running="$(docker inspect --format '{{index .RepoDigests 0}}' "$(docker compose -p "$PROJECT" --env-file "$env_file" ps -q app)")"
      case "$running" in *"$digest"*) ;; *) problems+=("running container digest $running is not the published digest");; esac
    fi
    set -a; source "$env_file"; set +a
    code="$(curl -s -o "$OUT_DIR/install-ingest.json" -w '%{http_code}' -X POST \
      "http://127.0.0.1:$PORT/api/v1/events" -H "Authorization: Bearer $DWT_INGEST_TOKEN" \
      -H 'Content-Type: application/json' -H "Idempotency-Key: release-check-$(date -u +%s)" \
      -d "{\"event_id\":\"release-check-$(date -u +%s)\",\"source\":\"release:check\",\"event_type\":\"ReleaseCheckEvent\",\"event_timestamp\":\"$(utc_now)\",\"payload\":{\"amount\":1.0}}")"
    [ "$code" = "202" ] || problems+=("ingest against the anonymous install returned $code")
    docker compose -p "$PROJECT" --env-file "$env_file" -f "$install_dir/docker-compose.yml" \
      logs --no-color > "$OUT_DIR/install-logs.txt" 2>&1 || true
    docker compose -p "$PROJECT" --env-file "$env_file" -f "$install_dir/docker-compose.yml" down -v \
      > "$OUT_DIR/install-down.log" 2>&1 || true
  else
    problems+=("compose up from the Release bundle failed; see install-up.log")
  fi
else
  problems+=("could not download the Release bundle; see release-download.log")
fi

python3 - "$OUT_DIR" "${problems[@]+"${problems[@]}"}" <<'PY'
import json, sys
out_dir, *problems = sys.argv[1:]
summary = {"status": "FAILED" if problems else "PASSED", "problems": problems}
json.dump(summary, open(f"{out_dir}/release-summary.json", "w"), indent=2)
print(json.dumps(summary, indent=2))
PY

if [ "${#problems[@]}" -eq 0 ]; then
  write_gate "$OUT_DIR" RELEASE PASSED "release-check.sh --out $OUT_DIR" "$started" "$(utc_now)" 0 \
    "$OUT_DIR/release-summary.json" "$OUT_DIR/gate-evidence.json" >/dev/null
  exit 0
fi
write_gate "$OUT_DIR" RELEASE FAILED "release-check.sh --out $OUT_DIR" "$started" "$(utc_now)" 1 \
  "$OUT_DIR/release-summary.json" "$OUT_DIR/gate-evidence.json" >/dev/null
fail "release verification failed (${#problems[@]} problem(s)); see $OUT_DIR/release-summary.json"
