#!/usr/bin/env bash
# Candidate package gate (G15): install the release artifacts in a fresh environment and prove
# the running image is the frozen one.
#
# Nothing is built here. The image is loaded from the exported tar, the compose file and the
# self-host tooling come from the bundle, and the stack runs in its own project, volumes, env
# file and port. Then health, authentication, ingestion and a restart are exercised.

set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../lib/common.sh
source "$SCRIPT_DIR/../lib/common.sh"

OUT_DIR=""
ARTIFACTS=""
VERSION="v1.0.0"
PORT=18082
PROJECT="dwt-package-check-$(date -u +%Y%m%dt%H%M%Sz)-$$"
while [ $# -gt 0 ]; do
  case "$1" in
    --out) OUT_DIR="$2"; shift 2 ;;
    --artifacts) ARTIFACTS="$2"; shift 2 ;;
    --version) VERSION="$2"; shift 2 ;;
    --port) PORT="$2"; shift 2 ;;
    --project) PROJECT="$2"; shift 2 ;;
    *) die "unknown argument: $1" ;;
  esac
done
[ -n "$OUT_DIR" ] || die "package-check.sh requires --out DIR"
[ -n "$ARTIFACTS" ] || die "package-check.sh requires --artifacts DIR (output of package-release.sh)"
mkdir -p "$OUT_DIR"
# Absolute, because checksum verification runs inside a subshell that changes directory.
OUT_DIR="$(cd "$OUT_DIR" && pwd)"
require_docker
require_python
started="$(utc_now)"

bundle="$ARTIFACTS/bundle"
[ -d "$bundle" ] || die "bundle directory missing in $ARTIFACTS"
expected_image_id="$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["image"]["id"])' \
  "$ARTIFACTS/release-manifest.json")"
expected_tar_sha="$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["image"]["exported_tar_sha256"])' \
  "$ARTIFACTS/release-manifest.json")"

work="$OUT_DIR/install"
rm -rf "$work"; mkdir -p "$work"
tar -xzf "$ARTIFACTS/driftwatch-tower-${VERSION}-bundle.tar.gz" -C "$work"

# 1. checksums must cover the shipped artifacts and match.
( cd "$ARTIFACTS" && shasum -a 256 -c checksums.txt > "$OUT_DIR/checksums-verify.txt" 2>&1 ) \
  || fail "checksums do not match the shipped artifacts; see $OUT_DIR/checksums-verify.txt"
grep -q 'driftwatch-tower-' "$OUT_DIR/checksums-verify.txt" || fail "checksums.txt does not cover the image export"

# 2. the exported tar must be the frozen image, not a rebuild.
actual_tar_sha="$(python3 -c "
import hashlib,sys
d=hashlib.sha256()
with open(sys.argv[1],'rb') as h:
    for c in iter(lambda: h.read(1<<20), b''): d.update(c)
print(d.hexdigest())" "$ARTIFACTS/driftwatch-tower-${VERSION}.tar")"
[ "$actual_tar_sha" = "$expected_tar_sha" ] \
  || fail "image export hash mismatch: manifest=$expected_tar_sha actual=$actual_tar_sha"

docker load -i "$ARTIFACTS/driftwatch-tower-${VERSION}.tar" > "$OUT_DIR/docker-load.txt" 2>&1 \
  || fail "docker load failed; see $OUT_DIR/docker-load.txt"
loaded_id="$(docker image inspect --format '{{.Id}}' driftwatch-tower:local)"
[ "$loaded_id" = "$expected_image_id" ] \
  || fail "loaded image id $loaded_id does not match the frozen id $expected_image_id"

# 3. fresh environment: new env file, new project, new volumes, own port.
env_file="$OUT_DIR/install.env"
"$work/scripts/selfhost.sh" init --env-file "$env_file" > "$OUT_DIR/selfhost-init.log" 2>&1 \
  || fail "selfhost init from the bundle failed; see $OUT_DIR/selfhost-init.log"
chmod 600 "$env_file"
{
  echo "DWT_APP_PORT=$PORT"
  echo "DWT_APP_IMAGE=$loaded_id"
} >> "$env_file"

cp "$work/docker-compose.yml" "$work/docker-compose.install.yml"
[ -z "$(docker ps -aq --filter "label=com.docker.compose.project=$PROJECT")" ] && \
[ -z "$(docker volume ls -q --filter "label=com.docker.compose.project=$PROJECT")" ] || fail "install project already owns resources; choose a new project"
cleanup_install() {
  docker compose -p "$PROJECT" --env-file "$env_file" -f "$work/docker-compose.install.yml" logs --no-color > "$OUT_DIR/compose-logs.txt" 2>&1 || true
  docker compose -p "$PROJECT" --env-file "$env_file" -f "$work/docker-compose.install.yml" down -v > "$OUT_DIR/compose-down.log" 2>&1 || true
}
trap cleanup_install EXIT

docker compose -p "$PROJECT" --env-file "$env_file" -f "$work/docker-compose.install.yml" up -d \
  > "$OUT_DIR/compose-up.log" 2>&1 || fail "compose up from the bundle failed; see $OUT_DIR/compose-up.log"

base="http://127.0.0.1:$PORT"
ready=0
for _ in $(seq 1 60); do
  if curl -fsS "$base/actuator/health/readiness" >/dev/null 2>&1; then ready=1; break; fi
  sleep 2
done
[ "$ready" = "1" ] || fail "installed stack did not become ready within 120s"

set -a; source "$env_file"; set +a
status_anon="$(curl -s -o /dev/null -w '%{http_code}' "$base/actuator/health")"
status_auth="$(curl -s -o /dev/null -w '%{http_code}' -u "$DWT_ADMIN_USERNAME:$DWT_ADMIN_PASSWORD" "$base/api/v1/events/recent")"

event_id="package-check-$(date -u +%s)"
ingest_code="$(curl -s -o "$OUT_DIR/ingest-response.json" -w '%{http_code}' \
  -X POST "$base/api/v1/events" \
  -H "Authorization: Bearer $DWT_INGEST_TOKEN" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $event_id" \
  -d "{\"event_id\":\"$event_id\",\"source\":\"package:check\",\"event_type\":\"PackageCheckEvent\",\"event_timestamp\":\"$(date -u +%Y-%m-%dT%H:%M:%SZ)\",\"payload\":{\"amount\":42.5,\"currency\":\"USD\"}}")"
ingestion_id="$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1])).get("ingestion_id",""))' \
  "$OUT_DIR/ingest-response.json" 2>/dev/null || true)"

[[ "$ingestion_id" =~ ^[a-f0-9-]{36}$ ]] || fail "invalid or missing ingestion_id"
processed=0
for _ in $(seq 1 30); do
  count="$(docker compose -p "$PROJECT" --env-file "$env_file" exec -T postgres sh -c \
    'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -tAc "$1"' sh \
    "select count(*) from raw_events r join processed_receipts p using (ingestion_id) where r.ingestion_id='$ingestion_id'" 2>/dev/null | tr -d '[:space:]')"
  if [ "${count:-0}" -eq 1 ]; then processed=1; break; fi
  sleep 2
done

# 4. restart on the installed stack must recover from its own volumes.
docker compose -p "$PROJECT" --env-file "$env_file" -f "$work/docker-compose.install.yml" restart app \
  > "$OUT_DIR/restart.log" 2>&1 || fail "restart failed; see $OUT_DIR/restart.log"
recovered=0
for _ in $(seq 1 60); do
  if curl -fsS "$base/actuator/health/readiness" >/dev/null 2>&1; then recovered=1; break; fi
  sleep 2
done

python3 - "$OUT_DIR" "$PROJECT" "$env_file" "$work" "$expected_image_id" "$status_anon" \
        "$status_auth" "$ingest_code" "$processed" "$recovered" "$ingestion_id" "$ARTIFACTS/driftwatch-tower-${VERSION}-bundle.tar.gz" <<'PY'
import hashlib, json, pathlib, subprocess, sys
(out_dir, project, env_file, work, image_id, status_anon, status_auth, ingest_code,
 processed, recovered, ingestion_id, bundle_tar) = sys.argv[1:13]

def compose(*args):
    return subprocess.run(["docker", "compose", "-p", project, "--env-file", env_file,
                           "-f", f"{work}/docker-compose.install.yml", *args],
                          capture_output=True, text=True).stdout.strip()

running_image = compose("ps", "-q", "app")
inspect = subprocess.run(["docker", "inspect", "--format", "{{.Image}}", running_image],
                         capture_output=True, text=True).stdout.strip()

summary = {
    "bundle_sha256": hashlib.sha256(pathlib.Path(bundle_tar).read_bytes()).hexdigest(),
    "image_id_expected": image_id,
    "image_id_running": inspect,
    "image_identity_match": inspect == image_id,
    "anonymous_health_status": int(status_anon or 0),
    "authenticated_api_status": int(status_auth or 0),
    "ingest_status": int(ingest_code or 0),
    "ingestion_id": ingestion_id,
    "raw_rows_after_ingest": int(processed),
    "readiness_after_restart": recovered == "1",
    "container_status": compose("ps", "--format", "{{.Name}} {{.Status}}").splitlines(),
}
problems = []
if not summary["image_identity_match"]:
    problems.append(f"running image {inspect} is not the frozen image {image_id}")
if summary["anonymous_health_status"] != 200:
    problems.append(f"anonymous health returned {status_anon}")
if summary["authenticated_api_status"] != 200:
    problems.append(f"authenticated API returned {status_auth}")
if summary["ingest_status"] != 202:
    problems.append(f"ingest returned {ingest_code}")
if summary["raw_rows_after_ingest"] < 1:
    problems.append("ingested event was not persisted")
if not summary["readiness_after_restart"]:
    problems.append("stack did not become ready again after a restart")
summary["problems"] = problems
with open(f"{out_dir}/install-summary.json", "w") as handle:
    json.dump(summary, handle, indent=2)
print(json.dumps(summary, indent=2))
sys.exit(1 if problems else 0)
PY
check_exit=$?


if [ "$check_exit" -eq 0 ]; then
  write_gate "$OUT_DIR" PACKAGE PASSED "package-check.sh --out $OUT_DIR" "$started" "$(utc_now)" 0 \
    "$OUT_DIR/install-summary.json" "$OUT_DIR/checksums-verify.txt" "$OUT_DIR/docker-load.txt" >/dev/null
  exit 0
fi
write_gate "$OUT_DIR" PACKAGE FAILED "package-check.sh --out $OUT_DIR" "$started" "$(utc_now)" 1 \
  "$OUT_DIR/install-summary.json" "$OUT_DIR/checksums-verify.txt" "$OUT_DIR/docker-load.txt" >/dev/null
fail "candidate package gate failed; see $OUT_DIR/install-summary.json"
