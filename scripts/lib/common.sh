#!/usr/bin/env bash
# Shared helpers for the DriftWatch acceptance scripts.
# Sourced by verify.sh and selfhost.sh; not executable on its own.

set -uo pipefail

DWT_SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DWT_REPO_ROOT="$(cd "$DWT_SCRIPT_DIR/../.." && pwd)"
DWT_DEFAULT_PORT=18080

log()  { printf '%s %s\n' "$(date -u +%H:%M:%SZ)" "$*" >&2; }
die()  { log "ERROR: $*"; exit 2; }
fail() { log "FAIL: $*"; exit 1; }

# Exit codes: 0 pass, 1 verification failure, 2 external prerequisite missing.
require_cmd() {
  command -v "$1" >/dev/null 2>&1 || die "required command not found: $1"
}

require_java_21() {
  require_cmd java
  local version
  version="$(java -version 2>&1 | head -1 | sed -E 's/.*"([0-9]+).*/\1/')"
  [ "$version" = "21" ] || die "JDK 21 is required (found java $version); set JAVA_HOME to a JDK 21 install"
}

require_docker() {
  require_cmd docker
  docker info >/dev/null 2>&1 || die "Docker daemon is not available"
  docker compose version >/dev/null 2>&1 || die "docker compose is not available"
}

require_python() {
  require_cmd python3
  python3 -c 'import json,urllib.request' >/dev/null 2>&1 || die "python3 standard library unavailable"
}

# Loads an env file without echoing values. Exports DWT_* variables.
load_env_file() {
  local file="$1"
  [ -f "$file" ] || die "env file not found: $file"
  set -a
  # shellcheck disable=SC1090
  source "$file"
  set +a
  [ -n "${DWT_POSTGRES_PASSWORD:-}" ] || die "DWT_POSTGRES_PASSWORD missing in $file"
  [ -n "${DWT_ADMIN_PASSWORD:-}" ] || die "DWT_ADMIN_PASSWORD missing in $file"
}

# Writes the machine-readable gate result.
write_gate() {
  local out_dir="$1" id="$2" status="$3" command="$4" started="$5" ended="$6" exit_code="$7"
  shift 7
  mkdir -p "$out_dir"
  python3 - "$out_dir" "$id" "$status" "$command" "$started" "$ended" "$exit_code" "$@" <<'PY'
import json, sys, os
out_dir, gate_id, status, command, started, ended, exit_code = sys.argv[1:8]
evidence = list(sys.argv[8:])
payload = {
    "id": gate_id,
    "status": status,
    "command": command,
    "started_at": started,
    "ended_at": ended,
    "exit_code": int(exit_code),
    "evidence_paths": evidence,
    "git_sha": os.popen("git -C " + json.dumps(os.getcwd()) + " rev-parse HEAD 2>/dev/null").read().strip() or None,
}
path = os.path.join(out_dir, "gate.json")
with open(path, "w") as handle:
    json.dump(payload, handle, indent=2)
print(path)
PY
}

utc_now() { date -u +%Y-%m-%dT%H:%M:%SZ; }

# Waits until a URL answers (any status) within a timeout; used for readiness polling.
wait_http() {
  local url="$1" timeout_seconds="$2" expected="${3:-200}"
  local waited=0
  while [ "$waited" -lt "$timeout_seconds" ]; do
    local code
    code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 "$url" || true)"
    [ "$code" = "$expected" ] && return 0
    sleep 2
    waited=$((waited + 2))
  done
  return 1
}

# Fails the run when the repository state is dirty in files that matter for a release.
assert_clean_git() {
  local dirty
  dirty="$(git -C "$DWT_REPO_ROOT" status --porcelain -- src pom.xml Dockerfile docker-compose.yml scripts | head -5)"
  [ -z "$dirty" ] || fail "working tree has uncommitted application changes; commit them before binding a gate"
}