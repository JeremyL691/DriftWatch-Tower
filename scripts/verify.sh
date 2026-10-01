#!/usr/bin/env bash
# DriftWatch Tower unified verification entry point.
#
#   scripts/verify.sh preflight --out DIR
#   scripts/verify.sh unit      --out DIR
#   scripts/verify.sh sca       --out DIR [--image IMAGE]
#   scripts/verify.sh compose   --project NAME --env-file FILE --out DIR [--keep] [--no-build]
#   scripts/verify.sh phase P2|P3|P4|P5|P5b|P5c|P6 --project NAME --env-file FILE --out DIR [--base URL]
#   scripts/verify.sh freeze    --out DIR [--image IMAGE]
#   scripts/verify.sh load      --rate N --duration SECONDS --project NAME --env-file FILE --out DIR
#   scripts/verify.sh package   --out DIR [--image IMAGE] [--version TAG] [--manifest FILE]
#   scripts/verify.sh soak-start --duration SECONDS --run-id ID --project NAME --env-file FILE --out DIR [--fault-plan FILE]
#   scripts/verify.sh soak-status --run-id ID
#   scripts/verify.sh soak-resume --run-id ID
#   scripts/verify.sh soak-report --run-id ID [--out DIR]
#   scripts/verify.sh release --project NAME --env-file FILE --out DIR [--version TAG] [--pr N]
#
# Exit codes: 0 pass, 1 verification failure, 2 external prerequisite missing or not implemented.
# Every command writes a machine-readable gate.json plus human-readable summary into --out.

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/common.sh
source "$SCRIPT_DIR/lib/common.sh"

TRIVY_IMAGE="aquasec/trivy:0.58.1"
APP_IMAGE_DEFAULT="driftwatch-tower:local"

COMMAND="${1:-}"; shift || true
ORIGINAL_ARGS=("$@")
OUT_DIR=".execution/verify/$(date -u +%Y%m%dT%H%M%SZ)-${COMMAND:-none}"
PROJECT=""
ENV_FILE=".execution/selfhost.env"
IMAGE=""
KEEP=0
NO_BUILD=0
RATE=""
DURATION=""
RUN_ID=""
PHASE_NAME=""
BASE=""
VERSION=""
MANIFEST=""
PR_NUMBER=""
FAULT_PLAN=""

usage() {
  sed -n '2,19p' "$0" | sed 's/^# \{0,1\}//'
  exit 2
}

while [ $# -gt 0 ]; do
  case "$1" in
    --out)       OUT_DIR="$2"; shift 2 ;;
    --project)   PROJECT="$2"; shift 2 ;;
    --env-file)  ENV_FILE="$2"; shift 2 ;;
    --image)     IMAGE="$2"; shift 2 ;;
    --rate)      RATE="$2"; shift 2 ;;
    --duration)  DURATION="$2"; shift 2 ;;
    --run-id)    RUN_ID="$2"; shift 2 ;;
    --base)      BASE="$2"; shift 2 ;;
    --version)   VERSION="$2"; shift 2 ;;
    --pr)        PR_NUMBER="$2"; shift 2 ;;
    --fault-plan) FAULT_PLAN="$2"; shift 2 ;;
    --manifest)  MANIFEST="$2"; shift 2 ;;
    --keep)      KEEP=1; shift ;;
    --no-build)  NO_BUILD=1; shift ;;
    --help|-h)   usage ;;
    P1|P2|P3|P4|P5|P5b|P5c|P6|P7) PHASE_NAME="$1"; shift ;;
    *) die "unknown argument: $1" ;;
  esac
done

mkdir -p "$OUT_DIR"
COMMAND_LINE="verify.sh ${COMMAND} ${ORIGINAL_ARGS[*]:-}"

finish_gate() { # id status started exit_code
  local evidence=()
  while IFS= read -r file; do evidence+=("$file"); done < <(find "$OUT_DIR" -type f ! -name gate.json | sort)
  write_gate "$OUT_DIR" "$1" "$2" "$COMMAND_LINE" "$3" "$(utc_now)" "$4" ${evidence[@]+"${evidence[@]}"}
}

compose() {
  [ -n "$PROJECT" ] || die "this command requires --project NAME"
  docker compose -p "$PROJECT" --env-file "$ENV_FILE" "$@"
}

# ---------------------------------------------------------------- preflight
cmd_preflight() {
  local started; started="$(utc_now)"
  require_java_21
  require_docker
  require_python
  require_cmd git
  require_cmd curl

  local failures=0
  {
    echo "preflight at ${started}"
    echo "repo: $DWT_REPO_ROOT"
    echo "git HEAD: $(git -C "$DWT_REPO_ROOT" rev-parse HEAD)"
    echo "git dirty (application paths): $(git -C "$DWT_REPO_ROOT" status --porcelain -- src pom.xml Dockerfile docker-compose.yml scripts | wc -l | tr -d ' ') files"
    echo "java: $(java -version 2>&1 | head -1)"
    echo "maven wrapper: $(grep -m1 distributionUrl "$DWT_REPO_ROOT/.mvn/wrapper/maven-wrapper.properties" | sed 's/.*apache-maven-//; s/-bin.zip//')"
    echo "docker: $(docker version --format '{{.Server.Version}}')"
    echo "compose: $(docker compose version)"
    echo "python: $(python3 --version)"
    echo "arch/cpu: $(uname -m) / $(sysctl -n hw.ncpu 2>/dev/null || nproc)"
    echo "ram bytes: $(sysctl -n hw.memsize 2>/dev/null || awk '/MemTotal/ {print $2*1024}' /proc/meminfo)"
    echo "disk free: $(df -h "$DWT_REPO_ROOT" | tail -1 | awk '{print $4}')"
  } > "$OUT_DIR/preflight.txt"

  local free_gib
  free_gib="$(df -g "$DWT_REPO_ROOT" | tail -1 | awk '{print $4}')"
  if [ "${free_gib:-0}" -lt 20 ]; then
    echo "INSUFFICIENT DISK: ${free_gib}GiB free, 20GiB recommended" >> "$OUT_DIR/preflight.txt"
    failures=$((failures + 1))
  fi

  local port="${DWT_APP_PORT:-$DWT_DEFAULT_PORT}"
  if lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1; then
    echo "port $port: already in use" >> "$OUT_DIR/preflight.txt"
  else
    echo "port $port: free" >> "$OUT_DIR/preflight.txt"
  fi
  for busy in 8080 5432 9092; do
    if lsof -nP -iTCP:"$busy" -sTCP:LISTEN >/dev/null 2>&1; then
      echo "note: port $busy is in use by another process; acceptance uses $port and never takes it over" >> "$OUT_DIR/preflight.txt"
    fi
  done

  if command -v gh >/dev/null 2>&1; then
    echo "gh: $(gh --version | head -1)" >> "$OUT_DIR/preflight.txt"
    gh auth status >/dev/null 2>&1 && echo "gh auth: ok" >> "$OUT_DIR/preflight.txt" || echo "gh auth: not logged in" >> "$OUT_DIR/preflight.txt"
  else
    echo "gh: not installed (release steps need it)" >> "$OUT_DIR/preflight.txt"
  fi

  cat "$OUT_DIR/preflight.txt"
  if [ "$failures" -gt 0 ]; then
    finish_gate PREFLIGHT FAILED "$started" 2 >/dev/null
    exit 2
  fi
  finish_gate PREFLIGHT PASSED "$started" 0 >/dev/null
}

# --------------------------------------------------------------------- unit
cmd_unit() {
  local started; started="$(utc_now)"
  require_java_21
  require_docker
  assert_clean_git

  if ! docker info >/dev/null 2>&1; then
    finish_gate UNIT NOT_RUN "$started" 2 >/dev/null
    die "Docker is required for the container-backed integration tests"
  fi

  run_suite() {
    ( cd "$DWT_REPO_ROOT" && ./mvnw clean test -Ddwt.requireDocker=true --batch-mode ) \
      > "$OUT_DIR/mvn-test.log" 2>&1
  }

  run_suite
  local exit_code=$?
  local attempts=1
  # Container startup on a busy machine occasionally fails before any test runs; retry once and
  # keep both logs so the gate never hides a real failure behind a flake.
  if [ "$exit_code" -ne 0 ] && grep -qE 'ContainerLaunchException|Wait strategy failed|Container startup failed' "$OUT_DIR/mvn-test.log"; then
    log "infrastructure failure while starting containers; retrying the suite once"
    mv "$OUT_DIR/mvn-test.log" "$OUT_DIR/mvn-test.attempt1.log"
    run_suite
    exit_code=$?
    attempts=2
  fi
  echo "$attempts" > "$OUT_DIR/attempts.txt"

  python3 - "$OUT_DIR" <<'PY'
import glob, json, os, sys, xml.etree.ElementTree as ET
out_dir = sys.argv[1]
seen = set()
tot = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0}
classes = []
for path in sorted(glob.glob('target/surefire-reports/TEST-*.xml'), key=os.path.getmtime):
    root = ET.parse(path).getroot()
    name = root.get('name')
    if name in seen:
        continue
    seen.add(name)
    counts = {k: int(root.get(k)) for k in ('tests', 'failures', 'errors', 'skipped')}
    for key, value in counts.items():
        tot[key] += value
    classes.append({"class": name, **counts})
with open(f"{out_dir}/unit-summary.json", "w") as handle:
    json.dump({"totals": tot, "classes": classes}, handle, indent=2)
print(json.dumps(tot))
PY

  cat "$OUT_DIR/unit-summary.json"
  local tests failures errors skipped
  tests="$(python3 -c 'import json,sys;d=json.load(open(sys.argv[1]))["totals"];print(d.get("tests",0))' "$OUT_DIR/unit-summary.json")"
  failures="$(python3 -c 'import json,sys;d=json.load(open(sys.argv[1]))["totals"];print(d.get("failures",0))' "$OUT_DIR/unit-summary.json")"
  errors="$(python3 -c 'import json,sys;d=json.load(open(sys.argv[1]))["totals"];print(d.get("errors",0))' "$OUT_DIR/unit-summary.json")"
  skipped="$(python3 -c 'import json,sys;d=json.load(open(sys.argv[1]))["totals"];print(d.get("skipped",0))' "$OUT_DIR/unit-summary.json")"

  if [ "$exit_code" -eq 0 ] && [ "$failures" = "0" ] && [ "$errors" = "0" ] && [ "$skipped" = "0" ] && [ "$tests" -gt 0 ]; then
    finish_gate UNIT PASSED "$started" 0 >/dev/null
  else
    finish_gate UNIT FAILED "$started" 1 >/dev/null
    fail "unit/container suite failed (tests=$tests failures=$failures errors=$errors skipped=$skipped; see $OUT_DIR/mvn-test.log)"
  fi
}

# ---------------------------------------------------------------------- sca
cmd_sca() {
  local started; started="$(utc_now)"
  require_docker
  local image="${IMAGE:-$APP_IMAGE_DEFAULT}"

  docker run --rm -v "$DWT_REPO_ROOT":/repo:ro -v dwt-trivy-cache:/root/.cache \
    "$TRIVY_IMAGE" --version > "$OUT_DIR/trivy-version.txt" 2>&1

  if ! docker run --rm -v "$DWT_REPO_ROOT":/repo:ro -v dwt-trivy-cache:/root/.cache \
      "$TRIVY_IMAGE" fs --scanners vuln --severity HIGH,CRITICAL --format json \
      --output /dev/stdout /repo > "$OUT_DIR/sca-dependencies.json" 2> "$OUT_DIR/sca-dependencies.err"; then
    finish_gate SCA NOT_RUN "$started" 2 >/dev/null
    die "Trivy dependency scan could not run (vulnerability database unavailable?); see $OUT_DIR/sca-dependencies.err"
  fi

  local dep_findings=0
  if docker image inspect "$image" >/dev/null 2>&1; then
    docker run --rm -v /var/run/docker.sock:/var/run/docker.sock -v dwt-trivy-cache:/root/.cache \
      "$TRIVY_IMAGE" image --scanners vuln --severity HIGH,CRITICAL --format json \
      --output /dev/stdout "$image" > "$OUT_DIR/sca-image.json" 2> "$OUT_DIR/sca-image.err" \
      || { finish_gate SCA NOT_RUN "$started" 2 >/dev/null; die "Trivy image scan failed; see $OUT_DIR/sca-image.err"; }
  else
    log "image $image not present locally; scanning dependencies only (run docker compose build first for image coverage)"
    echo '{"Results": [], "note": "image not present locally"}' > "$OUT_DIR/sca-image.json"
  fi

  dep_findings="$(python3 - "$OUT_DIR" <<'PY'
import json, sys
out = sys.argv[1]
total = 0
for name in ("sca-dependencies.json", "sca-image.json"):
    try:
        data = json.load(open(f"{out}/{name}"))
    except Exception:
        continue
    for result in data.get("Results") or []:
        total += len(result.get("Vulnerabilities") or [])
print(total)
PY
)"
  echo "HIGH/CRITICAL findings: $dep_findings"
  if [ "$dep_findings" = "0" ]; then
    finish_gate SCA PASSED "$started" 0 >/dev/null
  else
    finish_gate SCA FAILED "$started" 1 >/dev/null
    fail "SCA found $dep_findings HIGH/CRITICAL findings; see $OUT_DIR/sca-*.json"
  fi
}

# ------------------------------------------------------------------ compose
cmd_compose() {
  local started; started="$(utc_now)"
  require_docker
  load_env_file "$ENV_FILE"
  [ -n "$PROJECT" ] || die "compose requires --project NAME"

  local build_flag="--build"
  [ "$NO_BUILD" = "1" ] && build_flag=""

  local up_start; up_start=$(date -u +%s)
  compose up -d $build_flag > "$OUT_DIR/compose-up.log" 2>&1 || {
    finish_gate COMPOSE FAILED "$started" 1 >/dev/null
    fail "docker compose up failed; see $OUT_DIR/compose-up.log"
  }

  local port="${DWT_APP_PORT:-$DWT_DEFAULT_PORT}"
  local ready=0 waited=0
  while [ "$waited" -lt 240 ]; do
    state="$(compose ps --format '{{.Service}} {{.State}} {{.Health}}' 2>/dev/null)"
    if echo "$state" | grep -q 'app running healthy' \
       && echo "$state" | grep -q 'postgres running healthy' \
       && echo "$state" | grep -q 'kafka running healthy'; then
      ready=$(( $(date -u +%s) - up_start ))
      break
    fi
    sleep 2; waited=$((waited + 2))
  done
  echo "$state" > "$OUT_DIR/compose-state.txt"
  if [ "$ready" = "0" ]; then
    compose logs --tail 60 > "$OUT_DIR/compose-logs.txt" 2>&1 || true
    [ "$KEEP" = "1" ] || compose down >/dev/null 2>&1
    finish_gate COMPOSE FAILED "$started" 1 >/dev/null
    fail "stack did not become healthy within 240s; see $OUT_DIR/compose-state.txt and compose-logs.txt"
  fi

  # Readiness measured from container start must be within the 120s budget.
  local app_started_epoch app_ready_seconds
  app_started_epoch="$(docker inspect --format '{{.State.StartedAt}}' "$(compose ps -q app)" | python3 -c 'import sys,datetime;print(int(datetime.datetime.fromisoformat(sys.stdin.read().strip().replace("Z","+00:00")).timestamp()))')"
  app_ready_seconds=$(( up_start + ready - app_started_epoch ))
  echo "container-start-to-ready: ${app_ready_seconds}s (limit 120s)" > "$OUT_DIR/readiness.txt"

  # Host exposure: datastores must not be published, app only on loopback.
  docker ps --filter "name=${PROJECT}" --format '{{.Names}} {{.Ports}}' > "$OUT_DIR/ports.txt"
  grep -E 'postgres|kafka' "$OUT_DIR/ports.txt" | grep -q '0.0.0.0\|:::' && {
    finish_gate COMPOSE FAILED "$started" 1 >/dev/null
    fail "datastore ports are published on the host; see $OUT_DIR/ports.txt"
  }

  # Topics must exist with three partitions and no auto-creation.
  compose exec -T kafka bash -c '/opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:29092 --describe' \
    > "$OUT_DIR/topics.txt" 2>&1
  for topic in raw-events quality-events; do
    grep -q "Topic: ${topic}.*PartitionCount: 3" "$OUT_DIR/topics.txt" || {
      finish_gate COMPOSE FAILED "$started" 1 >/dev/null
      fail "topic ${topic} is missing or does not have 3 partitions; see $OUT_DIR/topics.txt"
    }
  done

  # End-to-end smoke through the ingest API using the configured bearer token.
  local smoke_ts event_id
  smoke_ts="$(utc_now)"
  event_id="verify-compose-$(date -u +%s)"
  local smoke_code
  smoke_code="$(curl -s -o "$OUT_DIR/smoke-post.json" -w '%{http_code}' \
    -X POST "http://127.0.0.1:${port}/api/v1/events" \
    -H "Authorization: Bearer ${DWT_INGEST_TOKEN}" -H 'Content-Type: application/json' \
    --data "{\"event_id\":\"${event_id}\",\"source\":\"verify-compose\",\"event_type\":\"compose_probe\",\"event_timestamp\":\"${smoke_ts}\",\"payload\":{\"bid\":1.0}})")"
  [ "$smoke_code" = "202" ] || { finish_gate COMPOSE FAILED "$started" 1 >/dev/null; fail "smoke ingest returned HTTP $smoke_code"; }

  local found=0
  for _ in $(seq 1 30); do
    if curl -s -u "${DWT_ADMIN_USERNAME}:${DWT_ADMIN_PASSWORD}" \
        "http://127.0.0.1:${port}/api/v1/events/recent?size=5" | grep -q "$event_id"; then
      found=1; break
    fi
    sleep 2
  done
  curl -s -u "${DWT_ADMIN_USERNAME}:${DWT_ADMIN_PASSWORD}" \
    "http://127.0.0.1:${port}/api/v1/events/recent?size=5" > "$OUT_DIR/smoke-recent.json"
  [ "$found" = "1" ] || { finish_gate COMPOSE FAILED "$started" 1 >/dev/null; fail "smoke event was not persisted; see $OUT_DIR/smoke-recent.json"; }

  if [ "$app_ready_seconds" -gt 120 ]; then
    finish_gate COMPOSE FAILED "$started" 1 >/dev/null
    fail "readiness took ${app_ready_seconds}s (>120s)"
  fi

  if [ "$KEEP" != "1" ]; then
    compose down >/dev/null 2>&1
  else
    log "--keep: leaving project $PROJECT running"
  fi
  finish_gate COMPOSE PASSED "$started" 0 >/dev/null
}

# -------------------------------------------------------------------- phase
cmd_phase() {
  local started; started="$(utc_now)"
  [ -n "$PHASE_NAME" ] || die "phase requires a phase name (P2..P5)"
  local check_script="$DWT_REPO_ROOT/scripts/phases/phase-${PHASE_NAME}.sh"
  if [ ! -x "$check_script" ]; then
    finish_gate "PHASE-${PHASE_NAME}" NOT_IMPLEMENTED "$started" 2 >/dev/null
    die "no phase check script for ${PHASE_NAME} yet ($check_script); it is added by that phase's implementation"
  fi
  "$check_script" --project "$PROJECT" --env-file "$ENV_FILE" --out "$OUT_DIR" ${BASE:+--base "$BASE"}
  local exit_code=$?
  [ "$exit_code" -eq 0 ] && finish_gate "PHASE-${PHASE_NAME}" PASSED "$started" 0 >/dev/null \
                          || finish_gate "PHASE-${PHASE_NAME}" FAILED "$started" "$exit_code" >/dev/null
  exit "$exit_code"
}

# --------------------------------------------------------------------- load
cmd_load() {
  local started; started="$(utc_now)"
  require_python
  require_docker
  [ -n "$RATE" ] || die "load requires --rate N"
  [ -n "$DURATION" ] || die "load requires --duration SECONDS"
  local check_script="$DWT_REPO_ROOT/scripts/phases/load-check.sh"
  if [ ! -x "$check_script" ]; then
    finish_gate LOAD NOT_IMPLEMENTED "$started" 2 >/dev/null
    die "the load harness lands with P6.1 ($check_script)"
  fi
  "$check_script" --rate "$RATE" --duration "$DURATION" --project "$PROJECT" \
    --env-file "$ENV_FILE" --out "$OUT_DIR" ${BASE:+--base "$BASE"}
  local exit_code=$?
  [ "$exit_code" -eq 0 ] && finish_gate LOAD PASSED "$started" 0 >/dev/null \
                          || finish_gate LOAD FAILED "$started" "$exit_code" >/dev/null
  exit "$exit_code"
}

# -------------------------------------------------------------------- freeze
cmd_freeze() {
  local started; started="$(utc_now)"
  local script="$DWT_REPO_ROOT/scripts/phases/freeze-candidate.sh"
  [ -x "$script" ] || die "freeze tooling missing: $script"
  "$script" --out "$OUT_DIR" --image "${IMAGE:-$APP_IMAGE_DEFAULT}"
  exit $?
}

# ------------------------------------------------------------------ package
# Builds the release artifacts for the frozen candidate and installs them from the artifacts
# alone in a fresh project (gate G15).
cmd_package() {
  local started; started="$(utc_now)"
  require_docker
  require_python
  local artifacts="$OUT_DIR/artifacts"
  local build_script="$DWT_REPO_ROOT/scripts/phases/package-release.sh"
  local check_script="$DWT_REPO_ROOT/scripts/phases/package-check.sh"
  if [ ! -x "$build_script" ] || [ ! -x "$check_script" ]; then
    finish_gate PACKAGE NOT_IMPLEMENTED "$started" 2 >/dev/null
    die "packaging lands with P6.1 ($build_script, $check_script)"
  fi
  "$build_script" --out "$artifacts" --image "${IMAGE:-$APP_IMAGE_DEFAULT}" --version "${VERSION:-v1.0.0}" \
    ${MANIFEST:+--manifest "$MANIFEST"} > "$OUT_DIR/package-release.log" 2>&1 \
    || { finish_gate PACKAGE FAILED "$started" 1 >/dev/null; fail "release packaging failed; see $OUT_DIR/package-release.log"; }
  "$check_script" --out "$OUT_DIR" --artifacts "$artifacts" --version "${VERSION:-v1.0.0}" \
    > "$OUT_DIR/package-check.log" 2>&1
  local exit_code=$?
  [ "$exit_code" -eq 0 ] && finish_gate PACKAGE PASSED "$started" 0 >/dev/null \
                          || finish_gate PACKAGE FAILED "$started" "$exit_code" >/dev/null
  exit "$exit_code"
}

# --------------------------------------------------------------- soak runner
cmd_soak() {
  local sub="$1"; shift
  require_python
  local extra=()
  [ -n "$DURATION" ] && extra+=(--duration "$DURATION")
  python3 "$DWT_REPO_ROOT/scripts/acceptance.py" "$sub" \
    --run-id "${RUN_ID:-}" ${extra[@]+"${extra[@]}"} \
    ${FAULT_PLAN:+--fault-plan "$FAULT_PLAN"} \
    --project "${PROJECT:-}" --env-file "$ENV_FILE" --out "$OUT_DIR"
}

# ------------------------------------------------------------------ release
cmd_release() {
  local started; started="$(utc_now)"
  local check_script="$DWT_REPO_ROOT/scripts/phases/release-check.sh"
  if [ ! -x "$check_script" ]; then
    finish_gate RELEASE NOT_IMPLEMENTED "$started" 2 >/dev/null
    die "release verification lands with P7 ($check_script)"
  fi
  # Only forward --project when it was given: the release check has its own default project and
  # forwarding an empty value would override it with nothing.
  "$check_script" --env-file "$ENV_FILE" --out "$OUT_DIR" \
    ${PROJECT:+--project "$PROJECT"} \
    --version "${VERSION:-v1.0.0}" ${PR_NUMBER:+--pr "$PR_NUMBER"}
  local exit_code=$?
  [ "$exit_code" -eq 0 ] && finish_gate RELEASE PASSED "$started" 0 >/dev/null \
                          || finish_gate RELEASE FAILED "$started" "$exit_code" >/dev/null
  exit "$exit_code"
}

case "$COMMAND" in
  preflight) cmd_preflight ;;
  unit)      cmd_unit ;;
  sca)       cmd_sca ;;
  compose)   cmd_compose ;;
  phase)     cmd_phase ;;
  freeze)    cmd_freeze ;;
  load)      cmd_load ;;
  package)   cmd_package ;;
  soak-start|soak-status|soak-resume|soak-report) cmd_soak "$COMMAND" ;;
  release)   cmd_release ;;
  *)         usage ;;
esac