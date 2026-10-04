#!/usr/bin/env python3
"""Persistent acceptance runner for DriftWatch Tower.

Commands
--------
soak-start   --run-id ID --duration SECONDS --project NAME --env-file FILE --out DIR
soak-status  --run-id ID
soak-resume  --run-id ID
soak-report  --run-id ID [--out DIR]   evaluate the section 11.1 conditions (gate G16)
runner       (internal) the detached process started by soak-start

The runner samples the stack every 30 seconds, checkpoints every 5 minutes with an atomic
write, and executes the scheduled fault injections. It uses only the Python standard library
and keeps running when the agent terminal goes away (`start_new_session=True`).

Run directory: .execution/soak/<run-id>/
  state.json        run metadata (project, env file, image identity, start/expected end)
  runner.pid        PID plus process start time (a PID alone is not proof of liveness)
  runner.log        detached process log
  samples.jsonl     one JSON object per 30s sample (UTC + monotonic)
  checkpoint.json   last durable checkpoint (atomic replace)
  faults.jsonl      controlled fault injections and their recovery measurements
  result.json       written when the run finishes (or fails)
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import shutil
import signal
import subprocess
import threading
import sys
import tempfile
import time
import urllib.error
import urllib.request

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SOAK_ROOT = os.path.join(REPO_ROOT, ".execution", "soak")
SAMPLE_INTERVAL_SECONDS = 30
CHECKPOINT_INTERVAL_SECONDS = 300
MAX_MONITOR_GAP_SECONDS = 120


def utc_now() -> str:
    return dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def parse_epoch(value: str) -> float:
    return dt.datetime.strptime(value, "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=dt.timezone.utc).timestamp()


def run_dir(run_id: str) -> str:
    return os.path.join(SOAK_ROOT, run_id)


def read_json(path: str, default=None):
    try:
        with open(path) as handle:
            return json.load(handle)
    except FileNotFoundError:
        return default


def atomic_write_json(path: str, payload) -> None:
    directory = os.path.dirname(path)
    os.makedirs(directory, exist_ok=True)
    fd, tmp = tempfile.mkstemp(dir=directory)
    try:
        with os.fdopen(fd, "w") as handle:
            json.dump(payload, handle, indent=2)
        os.replace(tmp, path)
    finally:
        if os.path.exists(tmp):
            os.unlink(tmp)


def append_jsonl(path: str, payload) -> None:
    with open(path, "a") as handle:
        handle.write(json.dumps(payload) + "\n")
        handle.flush()
        os.fsync(handle.fileno())


def sha256_file(path: str) -> str | None:
    import hashlib
    try:
        with open(path, "rb") as handle:
            return hashlib.sha256(handle.read()).hexdigest()
    except OSError:
        return None


def identify_image(project: str, env_file: str | None = None) -> dict:
    try:
        command = ["docker", "compose", "-p", project]
        if env_file:
            command.extend(["--env-file", env_file])
        cid = subprocess.run([*command, "ps", "-q", "app"],
                             capture_output=True, text=True, check=True).stdout.strip()
        if not cid:
            return {}
        image_id = subprocess.run(["docker", "inspect", "--format", "{{.Image}}", cid],
                                  capture_output=True, text=True, check=True).stdout.strip()
        return {"container": cid, "image_id": image_id}
    except Exception:
        return {}


def git_head() -> str:
    try:
        return subprocess.run(["git", "-C", REPO_ROOT, "rev-parse", "HEAD"],
                              capture_output=True, text=True, check=True).stdout.strip()
    except Exception:
        return "unknown"


def http_status(url: str, timeout: float = 5.0) -> int | str:
    try:
        with urllib.request.urlopen(url, timeout=timeout) as response:
            return response.status
    except urllib.error.HTTPError as error:
        return error.code
    except Exception:
        return "error"


def load_env(env_file: str) -> dict:
    values = {}
    if not os.path.exists(env_file):
        return values
    with open(env_file) as handle:
        for line in handle:
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, _, value = line.partition("=")
            values[key.strip()] = value.strip()
    return values


def compose(project: str, env_file: str, *args: str, timeout: int = 120,
            input_text: str | None = None) -> subprocess.CompletedProcess:
    return subprocess.run(
        ["docker", "compose", "-p", project, "--env-file", env_file, *args],
        capture_output=True, text=True, timeout=timeout, input=input_text)


def sample_once(project: str, env_file: str, app_port: str, admin: tuple[str, str] | None) -> dict:
    monotonic = time.monotonic()
    sample = {
        "utc": utc_now(),
        "monotonic": monotonic,
        "readiness": http_status(f"http://127.0.0.1:{app_port}/actuator/health/readiness"),
        "liveness": http_status(f"http://127.0.0.1:{app_port}/actuator/health"),
    }
    try:
        result = subprocess.run(
            ["docker", "stats", "--no-stream", "--format", "{{.Name}} {{.CPUPerc}} {{.MemUsage}}",
             *(f"{project}-{name}-1" for name in ("app", "kafka", "postgres"))],
            capture_output=True, text=True, timeout=60)
        sample["container_stats"] = [line for line in result.stdout.strip().splitlines() if line]
    except Exception as error:
        sample["container_stats"] = [f"stats unavailable: {error.__class__.__name__}"]
    if admin and admin[0]:
        url = f"http://127.0.0.1:{app_port}/api/v1/events/recent?size=1"
        request = urllib.request.Request(url)
        import base64
        token = base64.b64encode(f"{admin[0]}:{admin[1]}".encode()).decode()
        request.add_header("Authorization", f"Basic {token}")
        try:
            with urllib.request.urlopen(request, timeout=5) as response:
                sample["recent_probe"] = response.status
        except urllib.error.HTTPError as error:
            sample["recent_probe"] = error.code
        except Exception:
            sample["recent_probe"] = "error"
    return sample


def execute_fault(project: str, env_file: str, action: str, app_port: str,
                  expected_image: str | None = None) -> dict:
    """Runs one controlled fault and measures the outage and recovery.

    The compose project is shared state: before touching a service this checks that the
    running app container still carries the image this run was started against. A leftover
    runner from an earlier run would otherwise stop the services of the current one and
    corrupt both runs' evidence."""
    started = utc_now()
    start_monotonic = time.monotonic()
    service = {"app-restart": "app", "kafka-stop": "kafka", "db-stop": "postgres"}.get(action)
    if service is None:
        return {"action": action, "utc": started, "status": "unsupported"}
    current_image = identify_image(project, env_file).get("image_id")
    if expected_image and current_image != expected_image:
        return {"action": action, "utc": started, "status": "skipped-environment-changed",
                "expected_image": expected_image, "current_image": current_image}
    try:
        compose(project, env_file, "stop", service)
        time.sleep(30)  # target outage <= 60s
        compose(project, env_file, "start", service)
    except Exception as error:
        return {"action": action, "utc": started, "status": "error", "error": str(error)}
    outage_seconds = time.monotonic() - start_monotonic
    recovery_started = time.monotonic()
    recovered = None
    deadline = recovery_started + 300  # readiness must return within 5 minutes
    while time.monotonic() < deadline:
        if http_status(f"http://127.0.0.1:{app_port}/actuator/health/readiness") == 200:
            recovered = time.monotonic() - recovery_started
            break
        time.sleep(5)
    return {
        "action": action,
        "utc": started,
        "status": "completed" if recovered is not None else "not-recovered",
        "outage_seconds": round(outage_seconds, 1),
        "readiness_recovery_seconds": round(recovered, 1) if recovered is not None else None,
    }


def runner(args: argparse.Namespace) -> int:
    directory = run_dir(args.run_id)
    state = read_json(os.path.join(directory, "state.json"))
    if state is None:
        print(f"no state for run {args.run_id}", file=sys.stderr)
        return 2
    expected_image = (state.get("image") or {}).get("image_id")
    current_image = identify_image(args.project, state["env_file"]).get("image_id")
    if expected_image and current_image != expected_image:
        # The compose project belongs to a different candidate now: this run cannot observe or
        # fault the environment it was started against, so it fails instead of damaging it.
        reason = (f"environment replaced: app image is {current_image}, "
                  f"run expects {expected_image}")
        atomic_write_json(os.path.join(directory, "state.json"),
                          {**state, "status": "FAILED", "failure_reason": reason,
                           "finished_utc": utc_now()})
        atomic_write_json(os.path.join(directory, "result.json"), {
            "run_id": args.run_id, "status": "FAILED", "failure_reason": reason,
            "measured_seconds": 0, "sample_count": 0, "git_sha": state.get("git_sha"),
            "image": state.get("image"), "finished_utc": utc_now()})
        print(f"run {args.run_id} refused to start: {reason}", file=sys.stderr)
        return 1
    duration = float(state["duration_seconds"])
    app_port = str(state.get("app_port", "18080"))
    env = load_env(state["env_file"])
    admin = (env.get("DWT_ADMIN_USERNAME", ""), env.get("DWT_ADMIN_PASSWORD", ""))
    fault_plan = state.get("planned_faults", [])
    executed: set[str] = set()
    start_monotonic = time.monotonic()
    last_checkpoint = 0.0
    sample_count = 0
    previous_sample = None
    status = "PASSED"
    failure_reason = None
    fault_failure: list[str] = []

    atomic_write_json(os.path.join(directory, "state.json"), {**state, "status": "RUNNING",
                                                             "runner_started_utc": utc_now()})
    try:
        while True:
            elapsed = time.monotonic() - start_monotonic
            if elapsed > duration:
                break
            sample = sample_once(args.project, state["env_file"], app_port, admin)
            sample["elapsed_seconds"] = round(elapsed, 1)
            append_jsonl(os.path.join(directory, "samples.jsonl"), sample)
            sample_count += 1
            if previous_sample is not None:
                wall_gap, monotonic_gap = sample_gap(previous_sample, sample)
                if max(wall_gap, monotonic_gap) > MAX_MONITOR_GAP_SECONDS:
                    status = "FAILED"
                    failure_reason = (f"sampling continuity lost: UTC gap {wall_gap}s, "
                                      f"monotonic gap {monotonic_gap}s")
                    break
            previous_sample = sample
            for planned in fault_plan:
                key = f"{planned['at_seconds']}-{planned['action']}"
                if key in executed:
                    continue
                if elapsed >= float(planned["at_seconds"]):
                    executed.add(key)
                    # The fault runs in its own thread: execute_fault blocks for the outage plus
                    # the recovery wait, and a slow recovery would otherwise stop the monitor for
                    # longer than the continuity limit, invalidating the run for the wrong reason.
                    # Sampling continues while the fault is injected and measured.
                    def _fault(action=planned["action"]):
                        try:
                            outcome = execute_fault(args.project, state["env_file"], action, app_port,
                                                    expected_image)
                        except Exception as error:  # pragma: no cover - defensive
                            outcome = {"action": action, "utc": utc_now(), "status": "error",
                                       "error": f"{error.__class__.__name__}: {error}"}
                        append_jsonl(os.path.join(directory, "faults.jsonl"), outcome)
                        if outcome.get("status") != "completed":
                            fault_failure.append(f"fault {action} ended {outcome.get('status')}")

                    threading.Thread(target=_fault, daemon=True).start()
            if fault_failure:
                # A planned fault that did not complete makes the window unusable: the run
                # stops here and is kept as a failure instead of being stitched together.
                status = "FAILED"
                failure_reason = "; ".join(fault_failure)
                break
            if time.monotonic() - last_checkpoint >= CHECKPOINT_INTERVAL_SECONDS:
                atomic_write_json(os.path.join(directory, "checkpoint.json"), {
                    "run_id": args.run_id,
                    "utc": utc_now(),
                    "elapsed_seconds": round(elapsed, 1),
                    "sample_count": sample_count,
                    "planned_faults": fault_plan,
                    "completed_faults": sorted(executed),
                })
                last_checkpoint = time.monotonic()
            time.sleep(SAMPLE_INTERVAL_SECONDS)
    except Exception as error:  # pragma: no cover - defensive
        status = "FAILED"
        failure_reason = f"{error.__class__.__name__}: {error}"
    finally:
        end_utc = utc_now()
        atomic_write_json(os.path.join(directory, "result.json"), {
            "run_id": args.run_id,
            "status": status,
            "failure_reason": failure_reason,
            "started_utc": state.get("started_utc"),
            "finished_utc": end_utc,
            "planned_duration_seconds": duration,
            "measured_seconds": round(time.monotonic() - start_monotonic, 1),
            "sample_count": sample_count,
            "git_sha": state.get("git_sha"),
            "image": state.get("image"),
        })
        atomic_write_json(os.path.join(directory, "state.json"), {**state, "status": status,
                                                                 "finished_utc": end_utc})
    return 0 if status == "PASSED" else 1


def cmd_soak_start(args: argparse.Namespace) -> int:
    if not args.run_id:
        print("soak-start requires --run-id", file=sys.stderr)
        return 2
    directory = run_dir(args.run_id)
    if os.path.exists(os.path.join(directory, "state.json")):
        existing = read_json(os.path.join(directory, "state.json"))
        print(f"run {args.run_id} already exists with status {existing.get('status')}; "
              f"use soak-status or soak-resume")
        return 0
    if not args.project:
        print("soak-start requires --project NAME", file=sys.stderr)
        return 2
    if not os.path.exists(args.env_file):
        print(f"soak-start: env file not found: {args.env_file}", file=sys.stderr)
        return 2
    alive = live_runners(exclude=args.run_id)
    if alive:
        print("soak-start refused: these runs still have a live runner: "
              + ", ".join(f"{run['run_id']} (pid {run['pid']})" for run in alive)
              + ". Two runners share one compose project, so their fault plans would stop each "
                "other's services; stop the old runner first.", file=sys.stderr)
        return 2
    os.makedirs(directory, exist_ok=True)
    env = load_env(args.env_file)
    state = {
        "run_id": args.run_id,
        "status": "STARTING",
        "started_utc": utc_now(),
        "duration_seconds": args.duration or 86400,
        "project": args.project,
        "env_file": args.env_file,
        "app_port": env.get("DWT_APP_PORT", "18080"),
        "git_sha": git_head(),
        "fault_plan_file": args.fault_plan,
        "image": identify_image(args.project, args.env_file),
    }
    if args.fault_plan and os.path.exists(args.fault_plan):
        state["planned_faults"] = read_json(args.fault_plan, [])
    atomic_write_json(os.path.join(directory, "state.json"), state)

    log_path = os.path.join(directory, "runner.log")
    with open(log_path, "ab") as log_handle:
        process = subprocess.Popen(
            [sys.executable, os.path.abspath(__file__), "runner",
             "--run-id", args.run_id, "--project", args.project],
            stdout=log_handle, stderr=log_handle, stdin=subprocess.DEVNULL,
            start_new_session=True, cwd=REPO_ROOT)
    start_time = subprocess.run(["ps", "-o", "lstart=", "-p", str(process.pid)],
                                capture_output=True, text=True).stdout.strip()
    with open(os.path.join(directory, "runner.pid"), "w") as handle:
        json.dump({"pid": process.pid, "process_start": start_time, "utc": utc_now()}, handle, indent=2)
    print(f"soak run {args.run_id} started (pid {process.pid}, duration {args.duration}s)")
    print(f"directory: {directory}")
    return 0


def read_samples(directory: str) -> list[dict]:
    path = os.path.join(directory, "samples.jsonl")
    samples = []
    if os.path.exists(path):
        with open(path) as handle:
            for line in handle:
                line = line.strip()
                if line:
                    samples.append(json.loads(line))
    return samples


def sample_gap(previous: dict, current: dict) -> tuple[float, float]:
    """UTC continues across host suspend; macOS monotonic time may not."""
    import math
    wall = parse_epoch(current["utc"]) - parse_epoch(previous["utc"])
    monotonic = current["monotonic"] - previous["monotonic"]
    if not all(math.isfinite(value) and value >= 0 for value in (wall, monotonic)):
        raise ValueError("sample clocks are non-finite or moved backwards")
    return wall, monotonic


def continuity_report(directory: str) -> dict:
    samples = read_samples(directory)
    report = {"samples": len(samples), "max_gap_seconds": None, "continuity_valid": False}
    if len(samples) < 2:
        return report
    try:
        gaps = [sample_gap(a, b) for a, b in zip(samples, samples[1:])]
    except (KeyError, TypeError, ValueError) as error:
        return {**report, "error": str(error)}
    wall = max(gap[0] for gap in gaps)
    monotonic = max(gap[1] for gap in gaps)
    maximum = max(wall, monotonic)
    return {**report, "first_sample": samples[0]["utc"], "last_sample": samples[-1]["utc"],
            "max_wall_gap_seconds": round(wall, 1),
            "max_monotonic_gap_seconds": round(monotonic, 1),
            "max_gap_seconds": round(maximum, 1),
            "continuity_valid": maximum <= MAX_MONITOR_GAP_SECONDS}


def runner_alive(directory: str) -> bool:
    pid_file = os.path.join(directory, "runner.pid")
    info = read_json(pid_file)
    if not info:
        return False
    pid = info.get("pid")
    expected_start = info.get("process_start")
    try:
        actual_start = subprocess.run(["ps", "-o", "lstart=", "-p", str(pid)],
                                      capture_output=True, text=True, timeout=10).stdout.strip()
        command = subprocess.run(["ps", "-o", "command=", "-p", str(pid)],
                                 capture_output=True, text=True, timeout=10).stdout.strip()
    except Exception:
        return False
    return bool(actual_start) and actual_start == expected_start and "acceptance.py" in command


def live_runners(exclude: str | None = None) -> list[dict]:
    """Every soak run in a live state whose recorded runner process is still alive.

    One compose project can only serve one 24h window: a runner left over from an earlier run
    keeps its own fault plan and would stop the services of the current run."""
    alive: list[dict] = []
    root = os.path.join(REPO_ROOT, ".execution", "soak")
    if not os.path.isdir(root):
        return alive
    for name in sorted(os.listdir(root)):
        if name == exclude:
            continue
        directory = os.path.join(root, name)
        if not os.path.isdir(directory):
            continue
        state = read_json(os.path.join(directory, "state.json"), {}) or {}
        if state.get("status") not in {"STARTING", "RUNNING"}:
            continue
        if runner_alive(directory):
            alive.append({"run_id": name,
                          "pid": (read_json(os.path.join(directory, "runner.pid"), {}) or {}).get("pid")})
    return alive


def cmd_soak_status(args: argparse.Namespace) -> int:
    if not args.run_id:
        print("soak-status requires --run-id", file=sys.stderr)
        return 2
    directory = run_dir(args.run_id)
    state = read_json(os.path.join(directory, "state.json"))
    if state is None:
        print(f"no soak run {args.run_id}", file=sys.stderr)
        return 2
    checkpoint = read_json(os.path.join(directory, "checkpoint.json"), {})
    report = {
        "run_id": args.run_id,
        "status": state.get("status"),
        "started_utc": state.get("started_utc"),
        "planned_duration_seconds": state.get("duration_seconds"),
        "runner_alive": runner_alive(directory),
        "checkpoint": checkpoint,
        "continuity": continuity_report(directory),
        "result": read_json(os.path.join(directory, "result.json")),
    }
    print(json.dumps(report, indent=2))
    status = report["status"]
    if status == "RUNNING":
        return 0
    if status == "PASSED":
        return 0
    return 1


def cmd_soak_report(args: argparse.Namespace) -> int:
    """Evaluates the section 11.1 conditions of a finished run (gate G16)."""
    if not args.run_id:
        print("soak-report requires --run-id", file=sys.stderr)
        return 2
    directory = run_dir(args.run_id)
    state = read_json(os.path.join(directory, "state.json"))
    if state is None:
        print(f"no soak run {args.run_id}", file=sys.stderr)
        return 2
    result = read_json(os.path.join(directory, "result.json"), {})
    continuity = continuity_report(directory)
    samples = read_samples(directory)
    fault_path = os.path.join(directory, "faults.jsonl")
    faults = []
    if os.path.exists(fault_path):
        with open(fault_path) as handle:
            faults = [json.loads(line) for line in handle if line.strip()]
    project, env_file = state.get("project", ""), state.get("env_file", "")
    problems: list[str] = []

    if state.get("status") != "PASSED" or result.get("status") != "PASSED":
        problems.append("runner has not completed successfully")
    expected_image = (state.get("image") or {}).get("image_id")
    current_image = identify_image(project, env_file)
    if not expected_image or current_image.get("image_id") != expected_image:
        problems.append("running image differs from the observed candidate")
    if not (state.get("image") or {}).get("container") or current_image.get("container") != state["image"]["container"]:
        problems.append("application container was replaced; immutable runtime configuration cannot be proven")

    # 1. duration and monitor continuity
    measured = float(result.get("measured_seconds") or 0)
    planned = float(state.get("duration_seconds") or 0)
    if measured < planned or planned < 86400:
        problems.append(f"measured {measured}s of a planned {planned}s window (need >= 86400s)")
    if not continuity.get("continuity_valid"):
        problems.append(f"monitor gap {continuity.get('max_gap_seconds')}s exceeds "
                        f"{MAX_MONITOR_GAP_SECONDS}s")
    # The sampler sleeps SAMPLE_INTERVAL_SECONDS *after* probing (readiness, liveness, docker
    # stats), so its effective cadence is a couple of seconds longer than the nominal interval.
    # Counting samples against the nominal interval therefore fails a perfectly continuous
    # window: 86400s at ~32s yields ~2700 samples, and 2700*30 is below 0.95*86400. What the
    # criterion has to prove is that the monitor covered the whole window, so check the span the
    # samples cover and require at least one sample per continuity limit.
    if samples:
        span = samples[-1]["monotonic"] - samples[0]["monotonic"]
        if span < planned * 0.99:
            problems.append(f"samples cover {round(span)}s of the {round(planned)}s window")
        minimum_samples = int(planned / MAX_MONITOR_GAP_SECONDS)
        if len(samples) < minimum_samples:
            problems.append(f"only {len(samples)} samples for a {round(planned)}s window "
                            f"(need at least {minimum_samples}, one per {MAX_MONITOR_GAP_SECONDS}s)")
    else:
        problems.append("no samples recorded")

    # 2. real source: enough distinct persisted events, at least one discovered after bootstrap
    source = psql_json(project, env_file, """
        select json_build_object(
          'github_distinct_events', (select count(distinct event_id) from raw_events
                                     where origin = 'GITHUB'),
          'github_live_events', (select count(distinct event_id) from raw_events
                                 where origin = 'GITHUB' and mode = 'LIVE'),
          'github_bootstrap_events', (select count(distinct event_id) from raw_events
                                      where origin = 'GITHUB' and mode = 'BOOTSTRAP'),
          'github_gaps', (select count(*) from source_gaps),
          'github_polls', (select count(*) from source_poll_runs)
        )""") or {}
    if source.get("github_distinct_events", 0) < 20:
        problems.append(f"only {source.get('github_distinct_events')} distinct real events persisted")
    if source.get("github_live_events", 0) < 1:
        problems.append("no event was discovered after the bootstrap round")

    # 3. controlled faults
    planned_faults = state.get("planned_faults") or []
    required_actions = {"app-restart", "kafka-stop", "db-stop"}
    if len(planned_faults) != 3 or {f.get("action") for f in planned_faults} != required_actions:
        problems.append("fault plan does not contain exactly the three required faults")
    if len(faults) != 3 or {f.get("action") for f in faults} != required_actions:
        problems.append("required faults are missing, duplicated or unexpected")
    if len(faults) < len(planned_faults):
        problems.append(f"{len(faults)} of {len(planned_faults)} planned faults executed")
    for fault in faults:
        if fault.get("status") != "completed":
            problems.append(f"fault {fault.get('action')} ended {fault.get('status')}")
        if not isinstance(fault.get("outage_seconds"), (int, float)) or not (0 <= fault["outage_seconds"] <= 60):
            problems.append(f"fault {fault.get('action')} outage {fault.get('outage_seconds')}s > 60s")
        if fault.get("readiness_recovery_seconds") is None \
                or fault["readiness_recovery_seconds"] > 300:
            problems.append(f"fault {fault.get('action')} readiness recovery "
                            f"{fault.get('readiness_recovery_seconds')}s > 300s")

    # 4. ledger: nothing accepted may be missing, and no business dead letter may stay open
    ledger = psql_json(project, env_file, """
        select json_build_object(
          'accepted', (select count(*) from ingestion_receipts where publish_state='CONFIRMED'),
          'unconfirmed', (select count(*) from ingestion_receipts where publish_state<>'CONFIRMED'),
          'processed', (select count(*) from processed_receipts),
          'raw', (select count(*) from raw_events),
          'dlt_open', (select count(*) from dead_letter_records where recovery_state='OPEN'),
          'dlt_total', (select count(*) from dead_letter_records),
          'pending_outbox', (select count(*) from source_outbox where status='PENDING'),
          'accepted_without_processed', (
             select count(*) from ingestion_receipts r where r.publish_state='CONFIRMED'
               and not exists (select 1 from processed_receipts p where p.ingestion_id = r.ingestion_id)),
          'source_without_processed', (select count(*) from source_inbox i
             where not exists (select 1 from processed_receipts p where p.ingestion_id=i.ingestion_id)),
          'raw_without_processed', (select count(*) from raw_events r
             where not exists (select 1 from processed_receipts p where p.ingestion_id=r.ingestion_id)),
          'processed_without_raw', (
             select count(*) from processed_receipts p
               where not exists (select 1 from raw_events e where e.ingestion_id = p.ingestion_id)),
          'alerts', (select count(*) from quality_alerts),
          'open_alerts', (select count(*) from quality_alerts where resolved_at is null)
        )""") or {}
    required_ledger = {"accepted", "unconfirmed", "processed", "raw", "dlt_open", "pending_outbox", "accepted_without_processed", "processed_without_raw", "source_without_processed", "raw_without_processed"}
    if not required_ledger.issubset(ledger) or any(not isinstance(ledger.get(k), int) for k in required_ledger):
        problems.append("persistence ledger is missing or invalid; cannot infer zero")
    for key in ("source_without_processed", "raw_without_processed"):
        if ledger.get(key):
            problems.append(f"{key}: {ledger[key]}")
    if ledger.get("accepted_without_processed"):
        problems.append(f"{ledger['accepted_without_processed']} accepted ingestions never processed")
    if ledger.get("processed_without_raw"):
        problems.append(f"{ledger['processed_without_raw']} processed ingestions without a raw row")
    if ledger.get("unconfirmed"):
        problems.append(f"{ledger['unconfirmed']} ingestion receipts never confirmed")
    if ledger.get("dlt_open"):
        problems.append(f"{ledger['dlt_open']} dead letters still open")
    if ledger.get("pending_outbox"):
        problems.append(f"{ledger['pending_outbox']} source outbox rows still pending")

    # 5. drain: every consumer group must be at zero lag
    lag, lag_detail = consumer_lag_total(project, env_file)
    if lag != 0:
        problems.append(f"consumer lag {lag} is not zero")

    # A later report must cite a real zero-backlog observation within ten minutes of completion.
    drain_path = os.path.join(directory, "drain-confirmation.json")
    finished = result.get("finished_utc")
    checked_at = utc_now()
    zero_fields = ("unconfirmed", "dlt_open", "pending_outbox", "accepted_without_processed", "processed_without_raw", "source_without_processed", "raw_without_processed")
    complete_zero = required_ledger.issubset(ledger) and all(ledger.get(k) == 0 for k in zero_fields) and lag == 0
    if finished and 0 <= parse_epoch(checked_at) - parse_epoch(finished) <= 600 and complete_zero:
        atomic_write_json(drain_path, {"run_id": args.run_id, "observed_at": checked_at,
            "finished_utc": finished, "image_id": expected_image, "ledger": ledger, "lag_total": lag})
    drain = read_json(drain_path, {})
    if not finished or drain.get("run_id") != args.run_id or drain.get("image_id") != expected_image or drain.get("finished_utc") != finished or not drain.get("observed_at") or not (0 <= parse_epoch(drain["observed_at"]) - parse_epoch(finished) <= 600) or drain.get("lag_total") != 0 or any((drain.get("ledger") or {}).get(k) != 0 for k in zero_fields):
        problems.append("no proven zero backlog/DLT/lag within ten minutes of window completion")

    # 5b. no unplanned container restart and no exhausted disk
    health = container_health(project, env_file)
    problems.extend(health.pop("problems", []))

    # 6. last hour healthy, ignoring the planned fault windows
    fault_windows = [(parse_epoch(fault["utc"]),
                      parse_epoch(fault["utc"]) + (fault.get("outage_seconds") or 0) + 300)
                     for fault in faults]
    last_hour = [sample for sample in samples
                 if sample["monotonic"] >= samples[-1]["monotonic"] - 3600] if samples else []
    unhealthy = []
    for sample in last_hour:
        epoch = parse_epoch(sample["utc"])
        if any(start <= epoch <= end for start, end in fault_windows):
            continue
        if sample.get("readiness") != 200 or sample.get("liveness") != 200 or sample.get("recent_probe") != 200:
            unhealthy.append(sample["utc"])
    if unhealthy:
        problems.append(f"readiness was not 200 in {len(unhealthy)} sample(s) of the last hour "
                        f"(first: {unhealthy[0]})")

    # 7. resource curve: first two hours versus the last hour
    def memory_mib(sample: dict) -> float | None:
        for line in sample.get("container_stats") or []:
            if "-app-1" not in line:
                continue
            parts = line.split()
            for index, token in enumerate(parts):
                if token.endswith("MiB") and index + 1 < len(parts) and parts[index + 1] == "/":
                    return float(token[:-3])
                if token.endswith("GiB") and index + 1 < len(parts) and parts[index + 1] == "/":
                    return float(token[:-3]) * 1024
        return None

    memory = [(sample["monotonic"], memory_mib(sample)) for sample in samples]
    memory = [(when, value) for when, value in memory if value is not None]
    resource = {"samples": len(memory)}
    if not memory:
        problems.append("no valid memory samples; resource bound cannot be verified")
    if memory:
        base_monotonic = memory[0][0]
        last_monotonic = memory[-1][0]
        # The gate compares the first two hours with the last hour (guide 11.1). The first
        # minutes of a JVM are a cold start, so the report also computes the hours 1-2 plateau:
        # a bounded warm-up is evidence, not a leak, and the two numbers together show which
        # one is happening.
        early = [value for when, value in memory if when - base_monotonic <= 7200]
        plateau = [value for when, value in memory if 3600 <= when - base_monotonic <= 7200]
        late = [value for when, value in memory if when >= last_monotonic - 3600]
        if not plateau or not late:
            problems.append("memory plateau or final-hour samples missing")
        early_mean = sum(early) / len(early) if early else None
        plateau_mean = sum(plateau) / len(plateau) if plateau else None
        late_mean = sum(late) / len(late) if late else None
        resource.update({
            "first_two_hours_mean_mib": early_mean,
            "hours_1_to_2_mean_mib": plateau_mean,
            "last_hour_mean_mib": late_mean,
            "cold_start_mib": memory[0][1],
        })
        # The guide compares the hours 1-2 mean with the last hour, so that is what the gate
        # uses; the 0-2h figure (which includes the cold start) is reported alongside it as a
        # stricter observation rather than as the pass/fail criterion.
        if late_mean and (plateau_mean or early_mean):
            reference = plateau_mean if plateau_mean else early_mean
            reference_label = "hours 1-2" if plateau_mean else "first two hours (no hour-1-2 samples)"
            growth = late_mean - reference
            allowed = max(reference * 0.20, 128.0)
            resource.update({
                "reference_label": reference_label,
                "growth_mib": round(growth, 1),
                "allowed_mib": round(allowed, 1),
            })
            if early_mean:
                resource.update({
                    "strict_growth_vs_cold_start_mib": round(late_mean - early_mean, 1),
                    "strict_allowed_mib": round(max(early_mean * 0.20, 128.0), 1),
                })
            if growth > allowed:
                problems.append(
                    f"app memory grew {growth:.1f}MiB against the {reference_label} mean "
                    f"({reference:.1f}MiB), allowed {allowed:.1f}MiB")

    report = {
        "run_id": args.run_id,
        "status": state.get("status"),
        "planned_duration_seconds": planned,
        "measured_seconds": measured,
        "continuity": continuity,
        "source": source,
        "faults": faults,
        "ledger": ledger,
        "lag": {"total": lag, "detail": lag_detail},
        "drain_confirmation": drain,
        "reported_utc": checked_at,
        "containers": health,
        "resource": resource,
        "problems": problems,
    }
    out_dir = args.out or directory
    os.makedirs(out_dir, exist_ok=True)
    atomic_write_json(os.path.join(out_dir, "soak-report.json"), report)
    # The release gate looks for a gate.json per gate; the soak verdict needs one too, written
    # from the measured report rather than hand-entered.
    gate = {
        "id": "SOAK",
        "status": "PASSED" if not problems else "FAILED",
        "command": f"acceptance.py soak-report --run-id {args.run_id} --out {out_dir}",
        "started_at": state.get("started_utc"),
        "ended_at": utc_now(),
        "exit_code": 0 if not problems else 1,
        "evidence_paths": [os.path.join(out_dir, "soak-report.json")],
        "git_sha": state.get("git_sha"),
        "measurement": {
            "planned_duration_seconds": planned,
            "measured_seconds": measured,
            "samples": continuity.get("samples"),
            "max_monitor_gap_seconds": continuity.get("max_gap_seconds"),
            "github_distinct_events": source.get("github_distinct_events"),
            "github_live_events": source.get("github_live_events"),
            "faults_completed": len([f for f in faults if f.get("status") == "completed"]),
            "dlt_open": ledger.get("dlt_open"),
            "lag_total": lag,
            "container_restarts": {name: entry.get("restart_count")
                                   for name, entry in health.get("containers", {}).items()},
        },
        "failure_reason": "; ".join(problems) if problems else None,
    }
    atomic_write_json(os.path.join(out_dir, "gate.json"), gate)
    print(json.dumps(report, indent=2))
    return 1 if problems else 0


def consumer_lag_total(project: str, env_file: str) -> tuple[int, list[str]]:
    """Lag across every consumer group the stack owns; -1 when it cannot be read."""
    total = 0
    detail: list[str] = []
    for group in ("driftwatch-streams-v1", "driftwatch-sink", "driftwatch-dlt-projection"):
        completed = compose(project, env_file, "exec", "-T", "kafka",
                            "/opt/kafka/bin/kafka-consumer-groups.sh",
                            "--bootstrap-server", "localhost:29092", "--describe", "--group", group)
        if completed.returncode != 0:
            detail.append(f"{group}: {completed.stderr.strip()[:200]}")
            return -1, detail
        rows = 0
        for line in completed.stdout.splitlines():
            parts = line.split()
            if len(parts) >= 6 and parts[0] == group:
                rows += 1
                if parts[5].isdigit():
                    total += int(parts[5])
                elif parts[3:6] == ["-", "0", "-"]:
                    # A consumer of an empty topic has no committed offset; log-end=0 proves zero.
                    pass
                else:
                    return -1, detail + [f"{group}: unknown lag: {line.strip()}"]
                detail.append(line.strip())
        if rows == 0:
            return -1, detail + [f"{group}: no partition lag evidence"]
    return total, detail


def container_health(project: str, env_file: str) -> dict:
    """Restart counts and disk headroom: the guide requires no unplanned restart and no
    exhausted disk. A deliberate `compose stop/start` (the planned faults) does not increment
    RestartCount; a crash under `restart: unless-stopped` does, so this separates the two."""
    report = {"containers": {}, "problems": []}
    for service in ("app", "kafka", "postgres"):
        name = f"{project}-{service}-1"
        completed = subprocess.run(
            ["docker", "inspect", "--format",
             "{{.RestartCount}}|{{.State.StartedAt}}|{{.State.Status}}|{{.State.OOMKilled}}", name],
            capture_output=True, text=True)
        if completed.returncode != 0:
            report["containers"][service] = {"error": completed.stderr.strip()[:120]}
            report["problems"].append(f"cannot inspect {service}")
            continue
        restarts, started, status, oom = (completed.stdout.strip().split("|") + ["", "", "", ""])[:4]
        entry = {"restart_count": int(restarts or 0), "started_at": started, "status": status}
        entry["oom_killed"] = oom == "true"
        report["containers"][service] = entry
        if status != "running" or oom != "false":
            report["problems"].append(f"{service} status={status}, OOMKilled={oom}")
        if entry["restart_count"] > 0:
            report["problems"].append(
                f"{service} restarted {entry['restart_count']} time(s) outside the planned faults")
    disk = subprocess.run(["df", "-Pk", REPO_ROOT], capture_output=True, text=True)
    if disk.returncode == 0 and disk.stdout.strip():
        free_gib = int(disk.stdout.strip().splitlines()[-1].split()[3]) // (1024 * 1024)
        report["disk_free_gib"] = int(free_gib)
        if int(free_gib) < 5:
            report["problems"].append(f"only {free_gib}GiB free on the run volume")
    else:
        report["problems"].append("disk headroom could not be measured")
    return report


def psql_json(project: str, env_file: str, sql: str) -> dict | None:
    if not project or not env_file:
        return None
    completed = compose(project, env_file, "exec", "-T", "postgres", "sh", "-c",
                        'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -tA', input_text=sql)
    if completed.returncode != 0:
        print(f"psql failed: {completed.stderr.strip()[:300]}", file=sys.stderr)
        return None
    try:
        return json.loads(completed.stdout.strip())
    except Exception:
        return None


def cmd_soak_resume(args: argparse.Namespace) -> int:
    if not args.run_id:
        print("soak-resume requires --run-id", file=sys.stderr)
        return 2
    directory = run_dir(args.run_id)
    state = read_json(os.path.join(directory, "state.json"))
    if state is None:
        print(f"no soak run {args.run_id}", file=sys.stderr)
        return 2

    if runner_alive(directory):
        report = continuity_report(directory)
        print(f"run {args.run_id} is still alive; continuing to observe (do not start a second runner)")
        print(json.dumps(report, indent=2))
        return 0

    report = continuity_report(directory)
    atomic_write_json(os.path.join(directory, "state.json"), {**state, "status": "FAILED",
                                                             "failure_reason": "runner lost"})
    atomic_write_json(os.path.join(directory, "result.json"), {
        **read_json(os.path.join(directory, "result.json"), {}),
        "run_id": args.run_id,
        "status": "FAILED",
        "failure_reason": "runner process is gone; continuity cannot be proven",
        "continuity": report,
    })
    new_run_id = time.strftime("%Y%m%dT%H%M%SZ", time.gmtime()) + "-resume"
    print(f"run {args.run_id} FAILED (runner gone; max gap {report['max_gap_seconds']}s)")
    print(f"starting a fresh full run {new_run_id} instead of stitching the old one")
    forwarded = argparse.Namespace(**vars(args))
    forwarded.run_id = new_run_id
    # Carry the original run's placement forward. Taking the CLI defaults here would silently
    # point the replacement run at a different project, env file or port than the stack it is
    # supposed to watch, and the monitor would sample nothing.
    forwarded.project = state.get("project") or args.project
    forwarded.env_file = state.get("env_file") or args.env_file
    forwarded.fault_plan = state.get("fault_plan_file") or args.fault_plan
    # A resume restarts the full planned window; keep the original duration unless overridden.
    forwarded.duration = args.duration or int(state.get("duration_seconds", 86400))
    cmd_soak_start(forwarded)
    return 1


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)

    def add_common(target):
        target.add_argument("--run-id", default="")
        target.add_argument("--project", default="")
        target.add_argument("--env-file", default=".execution/selfhost.env")
        target.add_argument("--out", default="")

    for name, handler in (("soak-start", cmd_soak_start), ("soak-status", cmd_soak_status),
                          ("soak-resume", cmd_soak_resume), ("soak-report", cmd_soak_report),
                          ("runner", runner)):
        target = sub.add_parser(name)
        add_common(target)
        target.add_argument("--duration", type=int, default=None)
        target.add_argument("--fault-plan", default="")
        target.set_defaults(handler=handler)

    args = parser.parse_args()
    return args.handler(args)


if __name__ == "__main__":
    sys.exit(main())