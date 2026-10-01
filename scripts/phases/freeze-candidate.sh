#!/usr/bin/env bash
# P6.1 candidate freeze: pins the identity of everything a gate may later claim to have verified.
#
# Writes manifest.json with the commit, dirty state, source tree hash, config hash, dependency
# lock hash, image id/digest (when the image exists) and the machine/tool inventory the guide
# requires for a run record. Later gates copy the relevant fields instead of re-deriving them,
# so a gate can never silently bind to a different tree than the one it ran against.

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
    *) die "unknown argument: $1" ;;
  esac
done
[ -n "$OUT_DIR" ] || die "freeze-candidate.sh requires --out DIR"
mkdir -p "$OUT_DIR"
started="$(utc_now)"

require_python
require_java_21
IMAGE="${IMAGE:-driftwatch-tower:local}"

python3 - "$DWT_REPO_ROOT" "$OUT_DIR" "$IMAGE" "$started" <<'PY'
import hashlib, json, os, subprocess, sys

repo, out_dir, image, started = sys.argv[1:5]

def run(*args, cwd=repo):
    return subprocess.run(args, cwd=cwd, capture_output=True, text=True).stdout.strip()

def run_any(*args, cwd=repo):
    """Some tools (java -version) report on stderr; the identity matters either way."""
    result = subprocess.run(args, cwd=cwd, capture_output=True, text=True)
    return (result.stdout + result.stderr).strip()

def sha256_file(path):
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()

def tree_hash(roots):
    """Hash of (relative path, content hash) over every file under the given roots."""
    entries = []
    for root in roots:
        full = os.path.join(repo, root)
        if os.path.isfile(full):
            entries.append((root, sha256_file(full)))
        for base, dirs, files in os.walk(full):
            dirs[:] = sorted(d for d in dirs if d != "target")
            for name in sorted(files):
                path = os.path.join(base, name)
                entries.append((os.path.relpath(path, repo), sha256_file(path)))
    entries.sort()
    digest = hashlib.sha256()
    for path, file_hash in entries:
        digest.update(f"{path}\0{file_hash}\n".encode())
    return digest.hexdigest(), len(entries)

source_hash, source_files = tree_hash(
    ["src", "pom.xml", "Dockerfile", "docker-compose.yml", "docker-compose.dev.yml", ".mvn"])
# Gate tooling is versioned separately: the guide freezes application, dependencies, config,
# migrations, rules and image content, and a change to a check script must not masquerade as a
# new application version (nor a new application version hide behind an unchanged script).
tooling_hash, tooling_files = tree_hash(["scripts"])
config_paths = sorted(
    os.path.relpath(os.path.join(base, name), repo)
    for base, _, files in os.walk(os.path.join(repo, "src/main/resources"))
    for name in files if name.startswith("application"))
config_hash, _ = tree_hash(config_paths)
lock_hash, _ = tree_hash(["pom.xml", ".mvn/wrapper/maven-wrapper.properties"])

image_id = run("docker", "image", "inspect", "--format", "{{.Id}}", image)
image_digest = run("docker", "image", "inspect", "--format",
                   "{{index .RepoDigests 0}}", image) if image_id else ""

dirty = run("git", "status", "--porcelain")
manifest = {
    "run_id": os.path.basename(out_dir.rstrip("/")),
    "started_at_utc": started,
    "git_sha": run("git", "rev-parse", "HEAD"),
    "git_branch": run("git", "rev-parse", "--abbrev-ref", "HEAD"),
    "git_dirty": bool(dirty),
    "git_dirty_paths": dirty.splitlines(),
    "source_tree_hash": source_hash,
    "source_tree_files": source_files,
    "tooling_tree_hash": tooling_hash,
    "tooling_tree_files": tooling_files,
    "config_hash": config_hash,
    "config_files": config_paths,
    "dependency_lock_hash": lock_hash,
    "image": image,
    "image_id": image_id or None,
    "image_digest": image_digest or None,
    "platform": {
        "os": run("uname", "-s"),
        "arch": run("uname", "-m"),
        "cpu": run("sysctl", "-n", "hw.ncpu") or run("nproc"),
        "ram_bytes": run("sysctl", "-n", "hw.memsize"),
        "disk_free": run("df", "-h", repo).splitlines()[-1].split()[3] if run("df", "-h", repo) else None,
    },
    "tools": {
        "java": (run_any("java", "-version").splitlines() or [None])[0],
        "docker": run("docker", "version", "--format", "{{.Server.Version}}"),
        "compose": run("docker", "compose", "version"),
        "python": run("python3", "--version"),
        "node": run("node", "--version"),
    },
}
with open(os.path.join(out_dir, "manifest.json"), "w") as handle:
    json.dump(manifest, handle, indent=2)
print(json.dumps({k: manifest[k] for k in
                  ("git_sha", "git_dirty", "source_tree_hash", "config_hash",
                   "dependency_lock_hash", "image_id", "image_digest")}, indent=2))
PY

[ -f "$OUT_DIR/manifest.json" ] || fail "freeze failed to write manifest.json"
dirty="$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["git_dirty"])' "$OUT_DIR/manifest.json")"
if [ "$dirty" = "True" ]; then
  write_gate "$OUT_DIR" FREEZE FAILED "freeze-candidate.sh --out $OUT_DIR" "$started" "$(utc_now)" 1 \
    "$OUT_DIR/manifest.json" >/dev/null
  fail "candidate tree is dirty; commit before freezing a release candidate"
fi
write_gate "$OUT_DIR" FREEZE PASSED "freeze-candidate.sh --out $OUT_DIR" "$started" "$(utc_now)" 0 \
  "$OUT_DIR/manifest.json" >/dev/null
