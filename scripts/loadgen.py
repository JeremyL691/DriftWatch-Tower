#!/usr/bin/env python3
"""DriftWatch load harness (gate G14).

Offers a fixed event rate for a fixed duration through the public ingest API and measures what
the guide requires, without gaming either number:

* the offer count is a target that must actually be met (no "send fewer, look faster"),
* the acknowledgement p95 comes from this harness's own request-start to ack-response timing,
* the commit p95 comes from the application's received_at to transaction-commit histogram,
  read as a difference of Prometheus buckets across exactly the load window,
* every accepted ingestion_id is reconciled against processed/raw/alert rows afterwards, and
  anything left over must be an explicitly recorded dead letter.

Standard library only. Run it through scripts/verify.sh load.
"""

from __future__ import annotations

import argparse
import base64
import json
import os
import re
import statistics
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
import uuid
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone

SAMPLE_INTERVAL = 5.0
# Every consumer group this stack owns: the Streams topology and the database sink. The sink is
# what actually drains into processed rows, so its lag is the one that must reach zero.
LAG_GROUPS = ["driftwatch-streams-v1", "driftwatch-sink", "driftwatch-dlt-projection"]


def utc_now() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


class Client:
    """One HTTP connection per worker thread; the harness measures the full round trip."""

    def __init__(self, base: str, token: str):
        self.base = base.rstrip("/")
        self.token = token
        self.local = threading.local()

    def _connection(self):
        import http.client

        connection = getattr(self.local, "connection", None)
        parsed = urllib.parse.urlparse(self.base)
        if connection is None or getattr(self.local, "host", None) != parsed.netloc:
            connection = http.client.HTTPConnection(parsed.hostname, parsed.port, timeout=30)
            self.local.connection = connection
            self.local.host = parsed.netloc
        return connection

    def post_event(self, body: bytes, idempotency_key: str) -> tuple[float, int, dict]:
        parsed = urllib.parse.urlparse(self.base)
        path = (parsed.path or "") + "/api/v1/events"
        headers = {
            "Content-Type": "application/json",
            "Authorization": f"Bearer {self.token}",
            "Idempotency-Key": idempotency_key,
            "Content-Length": str(len(body)),
        }
        connection = self._connection()
        started = time.perf_counter()
        try:
            connection.request("POST", path, body=body, headers=headers)
            response = connection.getresponse()
            payload = response.read()
            elapsed = time.perf_counter() - started
            try:
                parsed_body = json.loads(payload or b"{}")
            except Exception:
                parsed_body = {"raw": payload[:200].decode("utf-8", "replace")}
            return elapsed, response.status, parsed_body
        except Exception as error:  # connection reset, timeout, ...
            elapsed = time.perf_counter() - started
            self.local.connection = None
            return elapsed, 0, {"error": type(error).__name__ + ": " + str(error)[:200]}


def prometheus_snapshot(base: str, credentials: str) -> dict[str, float]:
    """Parses the Prometheus scrape into {series-name-with-labels: value}."""
    request = urllib.request.Request(f"{base.rstrip('/')}/actuator/prometheus")
    request.add_header("Authorization", "Basic " + credentials)
    with urllib.request.urlopen(request, timeout=30) as response:
        text = response.read().decode()
    snapshot = {}
    for line in text.splitlines():
        if not line or line.startswith("#"):
            continue
        match = re.match(r"^(\S+)\s+([0-9.eE+-]+)$", line)
        if match:
            snapshot[match.group(1)] = float(match.group(2))
    return snapshot


def histogram_p95(before: dict[str, float], after: dict[str, float], metric: str) -> tuple[float | None, int]:
    """p95 over the window as a difference of cumulative bucket counts."""
    prefix = metric + "_bucket{"
    buckets = []
    for series, value in after.items():
        if series.startswith(prefix) and 'le="' in series:
            bound = series.split('le="', 1)[1].split('"', 1)[0]
            if bound == "+Inf":
                continue
            delta = value - before.get(series, 0.0)
            buckets.append((float(bound), delta))
    total = after.get(metric + "_count", 0.0) - before.get(metric + "_count", 0.0)
    if total <= 0 or not buckets:
        return None, int(total)
    buckets.sort()
    for bound, cumulative in buckets:
        if cumulative / total >= 0.95:
            return bound, int(total)
    return None, int(total)


def compose(project: str, env_file: str, *args: str, timeout: int = 120) -> str:
    command = ["docker", "compose", "-p", project, "--env-file", env_file, *args]
    result = subprocess.run(command, capture_output=True, text=True, timeout=timeout)
    if result.returncode != 0:
        raise RuntimeError(f"{' '.join(command)} failed: {result.stderr.strip()[:300]}")
    return result.stdout


def psql(project: str, env_file: str, sql: str) -> str:
    return compose(project, env_file, "exec", "-T", "postgres", "sh", "-c",
                   'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -tAc ' + json.dumps(sql)).strip()


def consumer_lag(project: str, env_file: str, groups: list[str]) -> tuple[int, list[str]]:
    """Total lag across every consumer group this stack owns."""
    total = 0
    lines: list[str] = []
    for group in groups:
        try:
            output = compose(project, env_file, "exec", "-T", "kafka",
                             "/opt/kafka/bin/kafka-consumer-groups.sh",
                             "--bootstrap-server", "localhost:29092", "--describe", "--group", group)
        except Exception as error:
            lines.append(f"{group}: {error}")
            total = -1
            continue
        for line in output.splitlines():
            parts = line.split()
            if len(parts) >= 6 and parts[0] != "GROUP":
                try:
                    total += int(parts[5])
                except ValueError:
                    continue
                lines.append(line.strip())
    return total, lines


def container_curve(project: str, env_file: str, stop: threading.Event, samples: list) -> None:
    """Resource curve for the containers this run owns (never other projects)."""
    while not stop.is_set():
        try:
            output = compose(project, env_file, "stats", "--no-stream", "--format",
                             "{{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}", timeout=60)
            for line in output.splitlines():
                parts = line.split("\t")
                if len(parts) == 3:
                    samples.append({"at": utc_now(), "container": parts[0],
                                    "cpu": parts[1], "mem": parts[2]})
        except Exception:
            pass
        stop.wait(SAMPLE_INTERVAL)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", required=True)
    parser.add_argument("--rate", type=int, required=True)
    parser.add_argument("--duration", type=int, required=True)
    parser.add_argument("--user", required=True)
    parser.add_argument("--password", required=True)
    parser.add_argument("--ingest-token", required=True)
    parser.add_argument("--project", required=True)
    parser.add_argument("--env-file", required=True)
    parser.add_argument("--out", required=True)
    parser.add_argument("--warmup", type=int, default=10)
    parser.add_argument("--workers", type=int, default=32)
    parser.add_argument("--source", default="load:generator")
    parser.add_argument("--event-type", default="LoadProbeEvent")
    args = parser.parse_args()

    os.makedirs(args.out, exist_ok=True)
    target_offers = args.rate * args.duration
    credentials = base64.b64encode(f"{args.user}:{args.password}".encode()).decode()
    client = Client(args.base, args.ingest_token)
    report: dict = {
        "run_id": os.path.basename(args.out.rstrip("/")),
        "started_at_utc": utc_now(),
        "base_url": args.base,
        "target_rate": args.rate,
        "target_duration_seconds": args.duration,
        "target_offers": target_offers,
        "source": args.source,
        "event_type": args.event_type,
        "warmup_seconds": args.warmup,
        "workers": args.workers,
    }

    # --- environment and cold/warm condition ------------------------------------------------
    project_json = psql(args.project, args.env_file,
                        "select json_build_object('now', now(), "
                        "'raw', (select count(*) from raw_events), "
                        "'receipts', (select count(*) from ingestion_receipts), "
                        "'processed', (select count(*) from processed_receipts), "
                        "'alerts', (select count(*) from quality_alerts), "
                        "'dlt_open', (select count(*) from dead_letter_records where recovery_state='OPEN'), "
                        "'db_bytes', pg_database_size(current_database()))")
    report["database_before"] = json.loads(project_json) if project_json else {}
    report["app_started_at"] = compose(args.project, args.env_file, "ps", "--format",
                                       "{{.Name}} {{.Status}}").strip()

    def event_body(index: int) -> tuple[bytes, str, str]:
        event_id = f"load-{index}-{uuid.uuid4().hex[:12]}"
        payload = {
            "event_id": event_id,
            "source": args.source,
            "event_type": args.event_type,
            "event_timestamp": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
            "payload": {
                "sequence": index,
                "amount": round(100 + (index % 997) * 1.5, 2),
                "currency": "USD",
                "status": "SETTLED" if index % 5 else "PENDING",
                "channel": ["web", "mobile", "partner"][index % 3],
            },
        }
        return json.dumps(payload).encode(), event_id, f"load-{index}"

    def offer(index: int) -> dict:
        body, event_id, key = event_body(index)
        elapsed, status, parsed = client.post_event(body, key)
        return {"index": index, "event_id": event_id, "latency": elapsed, "status": status,
                "ingestion_id": parsed.get("ingestion_id"), "error": parsed.get("error")}

    def run_phase(count: int, rate: int, sink: list, latch: dict, label: str) -> float:
        """Dispatches `count` offers paced at `rate` per second; returns the elapsed seconds."""
        started = time.perf_counter()
        lateness = []
        with ThreadPoolExecutor(max_workers=args.workers) as pool:
            futures = []
            for index in range(count):
                target = started + index / rate
                now = time.perf_counter()
                if target > now:
                    time.sleep(target - now)
                lateness.append(time.perf_counter() - target)
                futures.append(pool.submit(offer, index))
            for future in futures:
                sink.append(future.result())
        elapsed = time.perf_counter() - started
        latch[label] = {"dispatch_lateness_p95": round(statistics.quantiles(lateness, n=20)[18], 4)
                        if len(lateness) > 20 else round(max(lateness or [0]), 4)}
        return elapsed

    # --- warmup ----------------------------------------------------------------------------
    warm: list = []
    warm_latch: dict = {}
    run_phase(args.warmup * max(1, args.rate // 10), max(1, args.rate // 10), warm, warm_latch, "warmup")
    report["warmup"] = {
        "offers": len(warm),
        "accepted": sum(1 for item in warm if item["status"] == 202),
        "failed": sum(1 for item in warm if item["status"] != 202),
        **warm_latch,
    }
    report["condition"] = "warm: a short low-rate warmup precedes the timed window"

    # --- timed window ----------------------------------------------------------------------
    before = prometheus_snapshot(args.base, credentials)
    curve: list = []
    stop_curve = threading.Event()
    sampler = threading.Thread(target=container_curve,
                               args=(args.project, args.env_file, stop_curve, curve), daemon=True)
    sampler.start()

    offers: list = []
    latch: dict = {}
    window_started = utc_now()
    elapsed = run_phase(target_offers, args.rate, offers, latch, "window")
    window_ended = utc_now()
    after = prometheus_snapshot(args.base, credentials)
    stop_curve.set()

    accepted = [item for item in offers if item["status"] == 202]
    failed = [item for item in offers if item["status"] != 202]
    latencies = sorted(item["latency"] for item in accepted)

    def percentile(values: list[float], fraction: float) -> float:
        if not values:
            return 0.0
        return values[min(len(values) - 1, int(len(values) * fraction))]

    ack_p95 = percentile(latencies, 0.95)
    commit_p95, commit_samples = histogram_p95(before, after, "driftwatch_processing_duration_seconds")
    report["window"] = {
        "started_at_utc": window_started,
        "ended_at_utc": window_ended,
        "elapsed_seconds": round(elapsed, 2),
        "offers": len(offers),
        "achieved_rate": round(len(offers) / elapsed, 2),
        "accepted": len(accepted),
        "failed": len(failed),
        "failure_reasons": sorted({f"{item['status']}: {item.get('error') or ''}".strip()
                                   for item in failed}),
        "ack_latency_p50": round(percentile(latencies, 0.50), 4),
        "ack_latency_p95": round(ack_p95, 4),
        "ack_latency_p99": round(percentile(latencies, 0.99), 4),
        "ack_latency_max": round(max(latencies or [0.0]), 4),
        "commit_p95_from_histogram": commit_p95,
        "commit_samples_in_window": commit_samples,
        "dispatch_lateness_p95": latch.get("window", {}).get("dispatch_lateness_p95"),
        "server_ack_histogram_p95": histogram_p95(before, after,
                                                 "driftwatch_ingestion_ack_duration_seconds")[0],
    }

    # --- drain -----------------------------------------------------------------------------
    drain_started = time.perf_counter()
    stable = 0
    previous = -1
    lag_total = -1
    lag_lines: list[str] = []
    while time.perf_counter() - drain_started < 600:
        processed = int(psql(args.project, args.env_file, "select count(*) from processed_receipts") or 0)
        lag_total, lag_lines = consumer_lag(args.project, args.env_file, LAG_GROUPS)
        if processed == previous and lag_total <= 0:
            stable += 1
            if stable >= 3:
                break
        else:
            stable = 0
        previous = processed
        time.sleep(5)
    report["drain"] = {
        "seconds": round(time.perf_counter() - drain_started, 1),
        "consumer_groups": LAG_GROUPS,
        "lag_total": lag_total,
        "lag_detail": lag_lines,
    }

    # --- reconciliation --------------------------------------------------------------------
    ledger = json.loads(psql(args.project, args.env_file, """
        select json_build_object(
          'accepted_receipts', (select count(*) from ingestion_receipts where publish_state='CONFIRMED'),
          'accepted_unconfirmed', (select count(*) from ingestion_receipts where publish_state<>'CONFIRMED'),
          'processed', (select count(*) from processed_receipts),
          'raw', (select count(*) from raw_events),
          'raw_for_this_run', (select count(*) from raw_events where ingestion_id like 'ing%' and source='%s'),
          'alerts', (select count(*) from quality_alerts),
          'dlt_open', (select count(*) from dead_letter_records where recovery_state='OPEN'),
          'dlt_total', (select count(*) from dead_letter_records),
          'pending_outbox', (select count(*) from source_outbox where status='PENDING'),
          'db_bytes', pg_database_size(current_database()),
          'accepted_without_processed', (
             select count(*) from ingestion_receipts r
             where r.publish_state='CONFIRMED'
               and not exists (select 1 from processed_receipts p where p.ingestion_id = r.ingestion_id)),
          'processed_without_raw', (
             select count(*) from processed_receipts p
             where not exists (select 1 from raw_events e where e.ingestion_id = p.ingestion_id)),
          'open_dlt_ids', (select coalesce(json_agg(ingestion_id), '[]'::json)
                           from dead_letter_records where recovery_state='OPEN')
        )""" % args.source.replace("'", "''")))
    report["ledger"] = ledger

    # The harness knows which ids it offered; the database must know exactly those ids. Every
    # generated event_id carries the load- prefix, so the ledger can be compared without
    # shipping 180k literals into one statement.
    offered_ids = {item["ingestion_id"] for item in accepted if item["ingestion_id"]}
    with open(os.path.join(args.out, "offered-ingestion-ids.txt"), "w") as handle:
        handle.write("\n".join(sorted(offered_ids)))
    run_ledger = json.loads(psql(args.project, args.env_file, """
        select json_build_object(
          'accepted_in_db', (select count(*) from ingestion_receipts where event_id like 'load-%'),
          'raw_in_db', (select count(*) from raw_events where event_id like 'load-%'),
          'processed_in_db', (select count(*) from processed_receipts p
                              join raw_events e on e.ingestion_id = p.ingestion_id
                              where e.event_id like 'load-%'),
          'alerts_in_db', (select count(*) from quality_alerts a
                           join raw_events e on e.ingestion_id = a.ingestion_id
                           where e.event_id like 'load-%')
        )"""))
    report["offered_ids"] = {"count": len(offered_ids), **run_ledger}
    unmatched = abs(run_ledger.get("accepted_in_db", 0) - len(offered_ids))

    report["resource_curve"] = curve
    report["resource_curve_samples"] = len(curve)
    report["finished_at_utc"] = utc_now()

    problems = []
    if len(offers) != target_offers:
        problems.append(f"offered {len(offers)} of {target_offers} target events")
    if report["window"]["achieved_rate"] < args.rate * 0.99:
        problems.append(f"achieved rate {report['window']['achieved_rate']}/s below target {args.rate}/s")
    if len(failed) > target_offers * 0.01:
        problems.append(f"{len(failed)} of {len(offers)} offers failed")
    if ack_p95 > 1.0:
        problems.append(f"ack p95 {ack_p95:.3f}s above 1s")
    if commit_p95 is None:
        problems.append("commit p95 unavailable (no processing samples in the window)")
    elif commit_p95 > 5.0:
        problems.append(f"commit p95 {commit_p95:.3f}s above 5s")
    if ledger.get("dlt_open"):
        problems.append(f"{ledger['dlt_open']} dead letter(s) left open")
    if ledger.get("accepted_without_processed"):
        problems.append(f"{ledger['accepted_without_processed']} accepted ingestion(s) never processed")
    if ledger.get("processed_without_raw"):
        problems.append(f"{ledger['processed_without_raw']} processed ingestion(s) without a raw row")
    if ledger.get("accepted_unconfirmed"):
        problems.append(f"{ledger['accepted_unconfirmed']} ingestion receipt(s) never confirmed")
    if lag_total > 0:
        problems.append(f"consumer lag {lag_total} did not drain")
    if unmatched:
        problems.append(f"{unmatched} offered ingestion ids are unknown to the database")

    with open(os.path.join(args.out, "load-report.json"), "w") as handle:
        json.dump(report, handle, indent=2)
    print(json.dumps({key: report[key] for key in
                      ("window", "drain", "ledger", "offered_ids", "problems")}, indent=2))
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
