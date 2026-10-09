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
VERSION=""
REGISTRY_IMAGE="ghcr.io/jeremyl691/driftwatch-tower"
PR_NUMBER=""
CONTEXT=""
PORT=18086
PROJECT="dwt-release-check-$(date -u +%Y%m%dt%H%M%Sz)-$$"
while [ $# -gt 0 ]; do
  case "$1" in
    --context) CONTEXT="$2"; shift 2 ;;
    --out) OUT_DIR="$2"; shift 2 ;;
    --version) VERSION="$2"; shift 2 ;;
    --image) REGISTRY_IMAGE="$2"; shift 2 ;;
    --pr) PR_NUMBER="$2"; shift 2 ;;
    --port) PORT="$2"; shift 2 ;;
    --project) [ -n "$2" ] && PROJECT="$2"; shift 2 ;;
    --env-file|--keep|--no-build) shift 2 2>/dev/null || shift ;;
    *) die "unknown argument: $1" ;;
  esac
done
[ -n "$OUT_DIR" ] || die "release-check.sh requires --out DIR"
[ -n "$VERSION" ] || die "release-check.sh requires --version TAG"
mkdir -p "$OUT_DIR"
# Absolute, because checksum verification runs inside a subshell that changes directory.
OUT_DIR="$(cd "$OUT_DIR" && pwd)"
require_docker
require_python
require_cmd gh
started="$(utc_now)"
problems=()
abort_if_problems() {
  [ "${#problems[@]}" -eq 0 ] && return 0
  python3 - "$OUT_DIR/release-summary.json" "${problems[@]}" <<'PYFAIL'
import json,sys
json.dump({'status':'FAILED','problems':sys.argv[2:]},open(sys.argv[1],'w'),indent=2)
PYFAIL
  write_gate "$OUT_DIR" RELEASE FAILED "release-check --context $CONTEXT" "$started" "$(utc_now)" 1 "$OUT_DIR/release-summary.json" >/dev/null
  fail "release prerequisite rejected; see release-summary.json"
}


# --- 1. gate evidence for this candidate ------------------------------------------------------
[ -n "$CONTEXT" ] || die "release-check.sh requires --context FILE"
if ! python3 "$DWT_REPO_ROOT/scripts/release-context.py" --context "$CONTEXT" --published --out "$OUT_DIR/gate-evidence.json"; then
  write_gate "$OUT_DIR" RELEASE FAILED "release context preflight" "$started" "$(utc_now)" 1 "$OUT_DIR/gate-evidence.json" >/dev/null
  fail "release context rejected; no publication or installation attempted"
fi

# --- 1b. that evidence must belong to the released application surface --------------------------
# Recording each gate's commit is not enough: the guide rejects gate reports that are stale
# relative to the code. The gates are produced on the candidate commit while the release is cut
# from the merged default branch, so compare the application surface (the frozen roots) rather
# than the commit ids.
git -C "$DWT_REPO_ROOT" fetch --tags --quiet origin 2>/dev/null || true
released_sha="$(gh api "repos/:owner/:repo/commits/$VERSION" --jq .sha 2>/dev/null)"
if [ -z "$released_sha" ]; then
  problems+=("could not resolve the commit of tag $VERSION")
else
  expected_sha="$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["release_sha"])' "$CONTEXT")"
  [ "$released_sha" = "$expected_sha" ] || problems+=("release tag differs from designated main SHA")
  printf '%s\n' "$released_sha" > "$OUT_DIR/released-commit.txt"
  python3 - "$DWT_REPO_ROOT" "$OUT_DIR" "$released_sha" <<'PY'
import json, os, subprocess, sys
repo, out_dir, released = sys.argv[1], sys.argv[2], sys.argv[3]
roots = ["src", "pom.xml", "Dockerfile", "docker-compose.yml", "docker-compose.dev.yml", ".mvn"]
evidence = json.load(open(os.path.join(out_dir, "gate-evidence.json")))
problems, checked = [], {}
for gate_id, entry in sorted(evidence.get("gates", {}).items()):
    sha = entry.get("git_sha")
    if not sha:
        problems.append(f"{gate_id}: gate.json records no git_sha")
        continue
    if sha == released:
        checked[gate_id] = "same commit as the release"
        continue
    diff = subprocess.run(["git", "-C", repo, "diff", "--quiet", sha, released, "--", *roots],
                          capture_output=True, text=True)
    if diff.returncode == 0:
        checked[gate_id] = f"{sha[:8]} has the released application surface"
    elif diff.returncode == 128:
        problems.append(f"{gate_id}: cannot compare {sha[:8]} with {released[:8]} ({diff.stderr.strip()[:80]})")
    else:
        problems.append(f"{gate_id}: produced from {sha[:8]}, whose application surface differs "
                        f"from the released commit {released[:8]}")
evidence["released_commit"] = released
evidence["surface_check"] = checked
evidence["surface_problems"] = problems
json.dump(evidence, open(os.path.join(out_dir, "gate-evidence.json"), "w"), indent=2)
print(json.dumps({"released_commit": released, "surface_check": checked,
                  "surface_problems": problems}, indent=2))
PY
  python3 -c "
import json,sys
d=json.load(open('$OUT_DIR/gate-evidence.json'))
sys.exit(1 if d['surface_problems'] else 0)" || problems+=("gate evidence was not produced from the released application surface; see gate-evidence.json")
fi

# --- 2. the pull request was merged -----------------------------------------------------------
if [ -n "$PR_NUMBER" ]; then
  gh pr view "$PR_NUMBER" --json state,headRefOid,mergeCommit,url,statusCheckRollup > "$OUT_DIR/pull-request.json" 2>&1 \
    || problems+=("could not read pull request $PR_NUMBER")
  state="$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1])).get("state"))' "$OUT_DIR/pull-request.json" 2>/dev/null)"
  [ "$state" = "MERGED" ] || problems+=("pull request $PR_NUMBER is $state, not MERGED")
  python3 - "$OUT_DIR/pull-request.json" "$CONTEXT" <<'PYCI'
import json,sys
pr=json.load(open(sys.argv[1]));context=json.load(open(sys.argv[2]))
required={'Unit and topology','Real Kafka and PostgreSQL integration','Migration, retry and dead letter','Image and SCA','Dashboard at four viewports'}
assert pr['headRefOid']==context['pr_head_sha'], 'PR head does not match accepted CI head'
checks={c['name']:c for c in pr.get('statusCheckRollup') or []}
assert all(name in checks and checks[name].get('conclusion')=='SUCCESS' and checks[name].get('status')=='COMPLETED' for name in required), 'five exact-head CI jobs are not successful'
PYCI
  [ $? -eq 0 ] || problems+=("exact-head five-job CI not verified")

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

abort_if_problems

# --- 4. anonymous pull by digest ---------------------------------------------------------------
digest="$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["public_image_digest"])' "$CONTEXT")"
[ -n "$digest" ] || problems+=("the release body does not name an image digest")
clean_config="$(mktemp -d)"
# Docker Desktop installs CLI plugins outside the system paths. Register only
# the executable directory; the fresh config contains no auths or cred helpers.
if [ -d /Applications/Docker.app/Contents/Resources/cli-plugins ]; then
  python3 - "$clean_config/config.json" <<'PYPLUGIN'
import json,sys
open(sys.argv[1],'w').write(json.dumps({'cliPluginsExtraDirs':['/Applications/Docker.app/Contents/Resources/cli-plugins']}))
PYPLUGIN
fi
unset DOCKER_AUTH_CONFIG
export DOCKER_CONFIG="$clean_config"
stack_created=0
cleanup_release() {
  if [ "$stack_created" = 1 ]; then
    docker compose -p "$PROJECT" --env-file "$env_file" -f "$install_dir/docker-compose.yml" logs --no-color > "$OUT_DIR/install-logs.txt" 2>&1 || true
    docker compose -p "$PROJECT" --env-file "$env_file" -f "$install_dir/docker-compose.yml" down -v > "$OUT_DIR/install-down.log" 2>&1 || true
  fi
  rm -rf "$clean_config"
}
trap cleanup_release EXIT
if [ -n "$digest" ]; then
  log "anonymous pull of $REGISTRY_IMAGE@$digest"
  if DOCKER_CONFIG="$clean_config" docker pull "$REGISTRY_IMAGE@$digest" > "$OUT_DIR/anonymous-pull.txt" 2>&1; then
    grep -q "$digest" "$OUT_DIR/anonymous-pull.txt" \
      || problems+=("anonymous pull did not report the expected digest")
  else
    problems+=("anonymous pull by digest failed; see anonymous-pull.txt (is the package public?)")
  fi
fi
abort_if_problems
python3 "$DWT_REPO_ROOT/scripts/image-content.py" --context "$CONTEXT" --image "$REGISTRY_IMAGE@$digest" --out "$OUT_DIR/public-image-identity.json" \
  > "$OUT_DIR/public-image-identity.log" 2>&1 || problems+=("public image identity mismatch")
"$SCRIPT_DIR/phase-P6.sh" --image "$REGISTRY_IMAGE@$digest" --out "$OUT_DIR/public-security" \
  > "$OUT_DIR/public-security.log" 2>&1 || problems+=("public digest security gate failed")
abort_if_problems

# --- 5. anonymous install from the Release bundle ---------------------------------------------
install_dir="$OUT_DIR/install"
rm -rf "$install_dir"; mkdir -p "$install_dir"
release_url="$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["url"])' "$OUT_DIR/release.json")"
if python3 "$DWT_REPO_ROOT/scripts/public-assets.py" --context "$CONTEXT" --release-url "$release_url" --out "$install_dir" > "$OUT_DIR/release-download.log" 2>&1; then
  env_file="$install_dir/release.env"
  "$install_dir/scripts/selfhost.sh" init --env-file "$env_file" > "$OUT_DIR/install-init.log" 2>&1 \
    || problems+=("selfhost init from the Release bundle failed")
  {
    echo "DWT_APP_PORT=$PORT"
    [ -n "$digest" ] && echo "DWT_APP_IMAGE=$REGISTRY_IMAGE@$digest"
  } >> "$env_file"
  [ -z "$(docker ps -aq --filter "label=com.docker.compose.project=$PROJECT")" ] && \
  [ -z "$(docker volume ls -q --filter "label=com.docker.compose.project=$PROJECT")" ] || problems+=("project already owns resources; choose a fresh project")
  abort_if_problems
  stack_created=1
  if docker compose -p "$PROJECT" --env-file "$env_file" -f "$install_dir/docker-compose.yml" up -d \
       > "$OUT_DIR/install-up.log" 2>&1; then
    ready=0
    for _ in $(seq 1 60); do
      if curl -fsS "http://127.0.0.1:$PORT/actuator/health/readiness" >/dev/null 2>&1; then ready=1; break; fi
      sleep 2
    done
    [ "$ready" = "1" ] || problems+=("the anonymously installed stack did not become ready")
    if [ -n "$digest" ]; then
      running="$(docker inspect --format '{{.Image}}' "$(docker compose -p "$PROJECT" --env-file "$env_file" -f "$install_dir/docker-compose.yml" ps -q app)")"
      expected_running="$(docker image inspect --format '{{.Id}}' "$REGISTRY_IMAGE@$digest")"
      [ "$running" = "$expected_running" ] || problems+=("running container image does not match the anonymously pulled digest")
    fi
    python3 "$SCRIPT_DIR/release-runtime.py" --project "$PROJECT" --env-file "$env_file" \
      --compose "$install_dir/docker-compose.yml" --base "http://127.0.0.1:$PORT" --out "$OUT_DIR" \
      > "$OUT_DIR/runtime-check.log" 2>&1 || problems+=("live G17 runtime checks failed; see runtime-summary.json")

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
