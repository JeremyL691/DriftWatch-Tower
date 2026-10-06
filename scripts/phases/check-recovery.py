#!/usr/bin/env python3
"""Actual recovery drills on fresh acceptance resources, bound to a frozen image.

Run with --manifest FILE --legacy-image IMAGE --out NEW_DIRECTORY. Failed attempts
retain their resources and evidence. Successful attempts remove only resources created
by this run. Credentials stay in 0600 env files outside the evidence directory.
"""
import argparse
import base64
import datetime as dt
import hashlib
import http.cookiejar
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import time
import urllib.error
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[2]
POSTGRES = "postgres:16.15@sha256:1a6ab3f5345eb6dbe04a1349529caabdb0ab09293a09590fad07b2246bfa4b54"


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def quote(value):
    return "'" + str(value).replace("'", "''") + "'"


class Drill:
    def __init__(self, args):
        self.args = args
        self.out = Path(args.out).resolve()
        self.out.mkdir(parents=True, exist_ok=False)
        self.run_id = dt.datetime.now(dt.timezone.utc).strftime("%Y%m%d%H%M%S")
        self.projects = []
        self.password = secrets.token_hex(24)
        self.admin_password = secrets.token_hex(24)
        self.token = secrets.token_hex(32)
        self.credentials = [self.password, self.admin_password, self.token]
        self.manifest = json.loads(Path(args.manifest).read_text())
        self.image = self.manifest["image_id"]
        self.report = {"status": "RUNNING", "run_id": self.run_id,
                       "started_at": dt.datetime.now(dt.timezone.utc).isoformat(),
                       "candidate_sha": self.manifest["git_sha"], "image_id": self.image,
                       "source_tree_hash": self.manifest["source_tree_hash"],
                       "tool_sha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                       "legacy_source_sha": "082fd84d7fabee7d94e05b4dba842f0995a3775e",
                       "checks": {}, "projects": []}
        (self.out / "executed-tool.py").write_bytes(Path(__file__).read_bytes())
        self.save()

    def save(self):
        p = self.out / "recovery-report.json"
        temp = p.with_suffix(".tmp")
        temp.write_text(json.dumps(self.report, indent=2) + "\n")
        temp.replace(p)

    def record(self, name, data):
        self.report["checks"][name] = data
        self.save()
        print(f"{dt.datetime.now(dt.timezone.utc).isoformat()} {name}: recorded", flush=True)

    def command(self, args, *, input_bytes=None, timeout=60, check=True):
        p = subprocess.run(args, cwd=ROOT, input=input_bytes, capture_output=True, timeout=timeout)
        text = (p.stdout + p.stderr).decode(errors="replace")
        for secret in self.credentials:
            text = text.replace(secret, "[REDACTED]")
        with (self.out / "commands.log").open("a") as f:
            # Never log argument values: docker env/psql can carry credentials or payloads.
            f.write(f"{args[0]} exit={p.returncode}\n{text}\n")
        if check:
            require(p.returncode == 0, f"{args[0]} failed ({p.returncode}): {text[-1000:]}")
        return p

    def docker_json(self, *args):
        return json.loads(self.command(["docker", *args]).stdout)

    def wait(self, name, fn, seconds=240):
        deadline = time.monotonic() + seconds
        last = None
        while time.monotonic() < deadline:
            try:
                last = fn()
                if last:
                    return last
            except (RuntimeError, OSError, urllib.error.URLError) as e:
                last = str(e)
            time.sleep(2)
        raise RuntimeError(f"timeout waiting for {name}: {last}")

    def project(self, suffix, port):
        name = f"dwt-recovery-{self.run_id}-{suffix}"
        require(not self.command(["docker", "ps", "-aq", "--filter",
                                  f"label=com.docker.compose.project={name}"]).stdout.strip(),
                f"project already has containers: {name}")
        require(not self.command(["docker", "volume", "ls", "-q", "--filter",
                                  f"label=com.docker.compose.project={name}"]).stdout.strip(),
                f"project already has volumes: {name}")
        for resource in ["pgdata", "kafkadata", "streams-state"]:
            require(self.command(["docker", "volume", "inspect", f"{name}_{resource}"],
                                 check=False).returncode != 0, "existing named volume")
        require(self.command(["docker", "network", "inspect", f"{name}_driftwatch"],
                             check=False).returncode != 0, "existing project network")
        with socket.socket() as sock:
            sock.bind(("127.0.0.1", port))
        env = ROOT / ".execution" / f"{name}.env"
        require(not env.exists(), "env path already exists")
        values = {"DWT_POSTGRES_DB": "driftwatch", "DWT_POSTGRES_USER": "driftwatch",
                  "DWT_POSTGRES_PASSWORD": self.password, "DWT_ADMIN_USERNAME": "driftwatch",
                  "DWT_ADMIN_PASSWORD": self.admin_password, "DWT_INGEST_TOKEN": self.token,
                  "DWT_APP_PORT": str(port), "DWT_APP_IMAGE": self.image}
        with os.fdopen(os.open(env, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "w") as f:
            f.write("\n".join(f"{k}={v}" for k, v in values.items()) + "\n")
        p = {"name": name, "env": str(env), "port": port, "apps": []}
        self.projects.append(p)
        self.report["projects"].append({"name": name, "port": port, "fresh": True})
        self.save()
        return p

    def compose(self, p, *args, **kwargs):
        return self.command(["docker", "compose", "-f", str(ROOT / "docker-compose.yml"),
                             "-p", p["name"], "--env-file", p["env"], *args], **kwargs)

    def infra(self, p):
        self.compose(p, "up", "-d", "postgres", "kafka", timeout=180)
        self.wait("healthy infrastructure", lambda: all(
            self.docker_json("inspect", f"{p['name']}-{s}-1")[0]["State"].get(
                "Health", {}).get("Status") == "healthy" for s in ["postgres", "kafka"]))

    def verify_owned(self, name, project):
        c = self.docker_json("inspect", name)[0]
        labels = c["Config"].get("Labels") or {}
        require(labels.get("com.docker.compose.project") == project and
                labels.get("com.docker.compose.project.working_dir") == str(ROOT) and
                labels.get("com.docker.compose.project.config_files") == str(ROOT / "docker-compose.yml"),
                f"ownership mismatch: {name}")
        return c

    def app(self, p, suffix, image=None, db="driftwatch", github=False, extra=None, port=True):
        name = f"{p['name']}-{suffix}"
        env = ROOT / ".execution" / f"{name}-app.env"
        values = {"SPRING_DATASOURCE_URL": f"jdbc:postgresql://postgres:5432/{db}",
                  "SPRING_DATASOURCE_USERNAME": "driftwatch", "SPRING_DATASOURCE_PASSWORD": self.password,
                  "SPRING_KAFKA_BOOTSTRAP_SERVERS": "kafka:29092", "SERVER_ADDRESS": "0.0.0.0",
                  "SPRING_KAFKA_STREAMS_STATE_DIR": "/tmp/drill-streams",
                  "DRIFTWATCH_SOURCE_GITHUB_ENABLED": str(github).lower(),
                  "JAVA_TOOL_OPTIONS": "-Xmx768m"}
        if not image:
            values.update({"SPRING_PROFILES_ACTIVE": "selfhost",
                           "DRIFTWATCH_SECURITY_ADMIN_USERNAME": "driftwatch",
                           "DRIFTWATCH_SECURITY_ADMIN_PASSWORD": self.admin_password,
                           "DRIFTWATCH_SECURITY_INGEST_TOKENS": self.token})
        if extra:
            values.update(extra)
        with os.fdopen(os.open(env, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "w") as f:
            f.write("\n".join(f"{k}={v}" for k, v in values.items()) + "\n")
        args = ["docker", "run", "-d", "--name", name, "--network", f"{p['name']}_driftwatch",
                "--env-file", str(env)]
        for key, value in {"com.docker.compose.project": p["name"],
                           "com.docker.compose.project.service": "app",
                           "com.docker.compose.project.working_dir": str(ROOT),
                           "com.docker.compose.project.config_files": str(ROOT / "docker-compose.yml"),
                           "driftwatch.acceptance.run": self.run_id}.items():
            args.extend(["--label", f"{key}={value}"])
        if port:
            args.extend(["-p", f"127.0.0.1:{p['port']}:8080"])
        args.append(image or self.image)
        self.command(args)
        p["apps"].append(name)
        require(self.verify_owned(name, p["name"])["Image"] == (image or self.image), "app image differs")
        return name

    def stop_app(self, p, name):
        self.verify_owned(name, p["name"])
        logs = self.command(["docker", "logs", name], check=False).stdout.decode(errors="replace")
        for secret in self.credentials:
            logs = logs.replace(secret, "[REDACTED]")
        (self.out / f"{name}.log").write_text(logs)
        self.command(["docker", "stop", "--time", "30", name])
        self.command(["docker", "rm", name])
        p["apps"].remove(name)

    def http(self, p, path, method="GET", body=None, auth="admin", opener=None, csrf=False):
        headers = {}
        if auth == "admin":
            headers["Authorization"] = "Basic " + base64.b64encode(
                f"driftwatch:{self.admin_password}".encode()).decode()
        elif auth == "ingest":
            headers["Authorization"] = "Bearer " + self.token
        if body is not None:
            headers["Content-Type"] = "application/json"
        if csrf:
            cookies = [c.value for c in opener.cookie_jar if c.name == "XSRF-TOKEN"]
            require(bool(cookies), "missing actual CSRF cookie")
            headers["X-XSRF-TOKEN"] = cookies[-1]
        req = urllib.request.Request(f"http://127.0.0.1:{p['port']}{path}",
                                     data=json.dumps(body).encode() if body is not None else None,
                                     headers=headers, method=method)
        try:
            with (opener or urllib.request.build_opener()).open(req, timeout=15) as r:
                data = r.read().decode()
                return r.status, json.loads(data) if data.startswith(("{", "[")) else data
        except urllib.error.HTTPError as e:
            return e.code, e.read().decode()

    def ready(self, p, legacy=False):
        path = "/actuator/health" if legacy else "/actuator/health/readiness"
        self.wait("application readiness", lambda: self.http(p, path, auth=None)[0] == 200)

    def admin(self, p):
        jar = http.cookiejar.CookieJar()
        op = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
        op.cookie_jar = jar
        require(self.http(p, "/api/v1/alerts", opener=op)[0] == 200, "admin cookie initialization failed")
        return op

    def sql(self, p, sql, db="driftwatch"):
        return self.compose(p, "exec", "-T", "postgres", "psql", "-v", "ON_ERROR_STOP=1",
                            "-U", "driftwatch", "-d", db, "-Atc", sql).stdout.decode().strip()

    def count(self, p, query, db="driftwatch"):
        return int(self.sql(p, query, db))

    def table(self, p, table, db="driftwatch", where="true"):
        # Whitelisted identifiers are supplied only by this script.
        return json.loads(self.sql(p, f"SELECT COALESCE(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text),'[]')"
                                      f" FROM {table} t WHERE {where}", db))

    def snapshot(self, p, tables, db="driftwatch"):
        result = {}
        for t in tables:
            rows = self.table(p, t, db)
            result[t] = {"count": len(rows), "sha256": hashlib.sha256(
                json.dumps(rows, sort_keys=True, separators=(",", ":")).encode()).hexdigest()}
        return result

    def kafka(self, p, tool, *args, **kwargs):
        return self.compose(p, "exec", "-T", "kafka", f"/opt/kafka/bin/{tool}.sh",
                            "--bootstrap-server", "localhost:29092", *args, **kwargs)

    def offsets(self, p, topic):
        raw = self.kafka(p, "kafka-get-offsets", "--topic", topic).stdout.decode()
        return {int(parts[1]): int(parts[2]) for line in raw.splitlines()
                if len(parts := line.split(":")) == 3 and parts[0] == topic}

    def drain(self, p, group):
        def check():
            text = self.kafka(p, "kafka-consumer-groups", "--describe", "--group", group).stdout.decode()
            rows = [x.split() for x in text.splitlines() if x.startswith(group + " ")]
            # Kafka prints '-' for an uncommitted empty partition. It contains no backlog;
            # a missing offset for any nonempty partition must still fail the drain check.
            complete = rows and all(len(x) > 5 and (x[5] == "0" or
                                   (x[3] == "-" and x[4] == "0")) for x in rows)
            return text if complete else False
        return self.wait(f"consumer lag zero: {group}", check)

    def dump(self, p, name):
        file = self.out / name
        self.command([str(ROOT / "scripts/selfhost.sh"), "backup", "--project", p["name"],
                      "--env-file", p["env"], "--out", str(file)])
        require(file.stat().st_size > 0, "empty database backup")
        return file

    def restore(self, p, dump):
        self.command([str(ROOT / "scripts/selfhost.sh"), "restore", "--project", p["name"],
                      "--env-file", p["env"], "--dump", str(dump),
                      "--target-db", "driftwatch_restore"])

    def kafka_archive(self, p, name, restore=False):
        volume = f"{p['name']}_kafkadata"
        v = self.docker_json("volume", "inspect", volume)[0]
        require((v.get("Labels") or {}).get("com.docker.compose.project") == p["name"],
                "Kafka volume ownership mismatch")
        args = ["docker", "run", "--rm", "--network", "none", "--label",
                f"driftwatch.acceptance.run={self.run_id}", "--entrypoint", "tar",
                "-v", f"{volume}:/data" + ("" if restore else ":ro"),
                "-v", f"{self.out}:/evidence", self.image]
        args += ["xzf" if restore else "czf", f"/evidence/{name}", "-C", "/data", "."] if not restore else [
            "xzf", f"/evidence/{name}", "-C", "/data"]
        self.command(args, timeout=120)

    def upgrade(self):
        p = self.project("upgrade", 18111)
        self.infra(p)
        old = self.app(p, "legacy", image=self.legacy_image)
        self.ready(p, legacy=True)
        events = [self.event(f"legacy-{i}", "legacy_acceptance", late=i == 4) for i in range(5)]
        for event in events:
            require(self.http(p, "/api/v1/events", "POST", event, auth=None)[0] == 202, "old ingest failed")
        self.wait("five legacy DB rows", lambda: self.count(p, "SELECT count(*) FROM raw_events") == 5)
        drains = {g: self.drain(p, g) for g in ["driftwatch-streams", "driftwatch-sink"]}
        cutoff = self.offsets(p, "raw-events")
        require(len(cutoff) == 3 and sum(cutoff.values()) == 5, "legacy cutoff is incomplete")
        before_raw = self.table(p, "raw_events")
        before_alert = self.table(p, "quality_alerts")
        before_schema = self.table(p, "schema_versions")
        checksums = self.sql(p, "SELECT string_agg(version || ':' || checksum, ',' ORDER BY installed_rank)"
                               " FROM flyway_schema_history WHERE version IN ('1','2','3','4','5','6','7')")
        self.stop_app(p, old)
        dump = self.dump(p, "legacy-db.dump")
        self.verify_owned(f"{p['name']}-kafka-1", p["name"])
        self.compose(p, "stop", "kafka")
        self.kafka_archive(p, "legacy-kafka.tar.gz")
        self.compose(p, "start", "kafka")
        self.wait("Kafka after backup", lambda: self.docker_json("inspect", f"{p['name']}-kafka-1")[0][
            "State"].get("Health", {}).get("Status") == "healthy")
        self.record("legacy_backup_and_drain", {"status": "PASSED", "raw_rows": before_raw,
                    "alerts": before_alert, "schema": before_schema, "cutoff": cutoff,
                    "consumer_group_offsets": drains, "v1_v7_checksums": checksums,
                    "db_backup": dump.name, "kafka_backup": "legacy-kafka.tar.gz"})
        new = self.app(p, "candidate")
        self.ready(p)
        upgraded = self.table(p, "raw_events")
        require(len(upgraded) == len(before_raw), "upgrade changed old row count")
        for oldrow, row in zip(before_raw, upgraded):
            require(all(row.get(k) == v for k, v in oldrow.items()), "upgrade changed legacy row values/PK")
            require(row["ingestion_id"] == f"legacy-db:{row['id']}", "legacy identity migration mismatch")
        alert_ids = ",".join(str(int(row["id"])) for row in before_alert) or "NULL"
        current_alert = self.table(p, "quality_alerts", where=f"id IN ({alert_ids})")
        require(len(current_alert) == len(before_alert) and all(
            all(row.get(k) == val for k, val in oldrow.items()) for oldrow, row in
            zip(before_alert, current_alert)),
            "upgrade changed historical alerts")
        current_schema = self.table(p, "schema_versions")
        require(len(current_schema) == len(before_schema) and all(
            all(row.get(k) == val for k, val in oldrow.items()) for oldrow, row in
            zip(before_schema, current_schema)), "upgrade changed historical schema")
        after_checksums = self.sql(p, "SELECT string_agg(version || ':' || checksum, ',' ORDER BY installed_rank)"
                                     " FROM flyway_schema_history WHERE version IN ('1','2','3','4','5','6','7')")
        require(checksums == after_checksums, "V1-V7 changed")
        require(self.offsets(p, "raw-events") == cutoff, "upgrade touched old topic data")
        self.record("historical_upgrade", {"status": "PASSED", "old_rows_preserved": len(upgraded),
                    "v1_v7_checksums_unchanged": True, "old_topic_preserved": True})
        backlog = [self.event(f"backlog-{i}", "legacy_acceptance") for i in range(2)]
        self.kafka(p, "kafka-console-producer", "--topic", "raw-events",
                   "--producer-property", "acks=all", input_bytes=("\n".join(map(json.dumps, backlog)) + "\n").encode())
        end = self.offsets(p, "raw-events")
        require(sum(end.values()) - sum(cutoff.values()) == 2, "controlled backlog count mismatch")
        spec = ",".join(f"{k}:{v}" for k, v in sorted(cutoff.items()))
        for attempt in range(2):
            bridge = self.app(p, f"bridge-{attempt}", port=False, extra={
                "DRIFTWATCH_BRIDGE_ENABLED": "true", "DRIFTWATCH_BRIDGE_OFFSETS": spec,
                "DRIFTWATCH_BRIDGE_REPORT_PATH": "/tmp/bridge-report.json", "DRIFTWATCH_STREAMS_ENABLED": "false"})
            result = self.wait("one-shot bridge report", lambda: self.command(
                ["docker", "exec", bridge, "cat", "/tmp/bridge-report.json"], check=False).stdout.strip())
            data = json.loads(result)
            require(data["status"] == "COMPLETED" and data["published"] == 2, "bridge did not publish exact backlog")
            (self.out / f"bridge-{attempt}.json").write_text(json.dumps(data, indent=2))
            self.stop_app(p, bridge)
            self.wait("exact two bridged rows", lambda: self.count(p,
                "SELECT count(*) FROM raw_events WHERE origin='LEGACY'") == 2)
            self.drain(p, "driftwatch-sink")
        rows = self.table(p, "raw_events", where="origin='LEGACY'")
        expected_ids = {
            str(uuid.UUID(bytes=hashlib.md5(f"legacy-kafka:raw-events:{partition}:{offset}".encode()).digest(), version=3))
            for partition, limit in end.items() for offset in range(cutoff[partition], limit)
        }
        require({row["ingestion_id"] for row in rows} == expected_ids, "unstable bridge identities")
        require({row["event_id"] for row in rows} == {event["event_id"] for event in backlog},
                "bridge persisted a different backlog")
        for row in rows:
            require(self.count(p, "SELECT count(*) FROM processed_receipts WHERE ingestion_id=" +
                               quote(row["ingestion_id"])) == 1, "bridge receipt duplicated")
        self.record("legacy_backlog_bridge", {"status": "PASSED", "cutoff": cutoff, "end": end,
                    "published_each_attempt": 2, "logical_rows_after_two_attempts": 2,
                    "rows": rows, "already_processed_history_replayed": False})
        self.stop_app(p, new)
        naive = self.app(p, "unsupported-image-only", image=self.legacy_image)
        # Observe actual lifecycle; words such as 'migration' are not error evidence.
        time.sleep(25)
        c = self.verify_owned(naive, p["name"])
        try:
            status, _ = self.http(p, "/actuator/health", auth=None)
        except OSError:
            status = None
        self.record("image_only_rollback_observation", {"container_running": c["State"]["Running"],
                    "exit_code": c["State"]["ExitCode"], "health_http": status,
                    "supported_rollback": False, "policy": "restore database and Kafka backup with historical image"})
        self.stop_app(p, naive)
        self.shutdown(p)
        r = self.project("rollback", 18112)
        self.command(["docker", "volume", "create", "--label", f"com.docker.compose.project={r['name']}",
                      "--label", "com.docker.compose.volume=kafkadata", f"{r['name']}_kafkadata"])
        self.kafka_archive(r, "legacy-kafka.tar.gz", restore=True)
        self.infra(r)
        self.restore(r, dump)
        require(self.table(r, "raw_events", "driftwatch_restore") == before_raw, "restored legacy rows differ")
        require(self.table(r, "quality_alerts", "driftwatch_restore") == before_alert, "restored old alerts differ")
        require(self.table(r, "schema_versions", "driftwatch_restore") == before_schema, "restored old schema differs")
        require(self.offsets(r, "raw-events") == cutoff, "restored Kafka cutoff differs")
        old = self.app(r, "restored-legacy", image=self.legacy_image, db="driftwatch_restore")
        self.ready(r, legacy=True)
        self.drain(r, "driftwatch-streams")
        self.drain(r, "driftwatch-sink")
        require(self.table(r, "raw_events", "driftwatch_restore") == before_raw, "rollback replay duplicated historical rows")
        self.record("backup_based_rollback", {"status": "PASSED", "fresh_project": r["name"],
                    "db": "driftwatch_restore", "restored_raw": len(before_raw),
                    "old_app_ready": True, "kafka_cutoffs_match": True, "lag_zero": True})
        self.stop_app(r, old)
        self.shutdown(r)

    def event(self, event_id, source, late=False):
        timestamp = dt.datetime.now(dt.timezone.utc) - dt.timedelta(minutes=30 if late else 0)
        return {"event_id": event_id, "source": source, "event_type": "recovery_acceptance",
                "event_timestamp": timestamp.isoformat(), "payload": {"bid": 10 + secrets.randbelow(1000)}}

    def backup_restore(self):
        p = self.project("backup", 18113)
        self.infra(p)
        app = self.app(p, "candidate", github=True)
        self.ready(p)
        self.wait("real-source inbox and applied checkpoint", lambda: self.count(p,
            "SELECT count(*) FROM source_inbox") > 0 and self.count(p,
            "SELECT count(*) FROM collector_state WHERE etag_applied IS NOT NULL") > 0, seconds=360)
        for i in range(4):
            code, response = self.http(p, "/api/v1/events", "POST", self.event(
                f"backup-{i}", "backup_acceptance", late=i == 3), auth="ingest")
            require(code == 202 and response.get("ingestion_id"), "candidate ingest failed")
        self.wait("backup inputs and alert", lambda: self.count(p,
            "SELECT count(*) FROM raw_events WHERE source='backup_acceptance'") == 4 and self.count(p,
            "SELECT count(*) FROM quality_alerts WHERE source='backup_acceptance' AND status<>'RESOLVED'") > 0)
        self.persistent_sink_outage(p)
        self.wait("all outbox rows published", lambda: self.count(p,
            "SELECT count(*) FROM source_outbox WHERE status='PENDING'") == 0 and self.count(p,
            "SELECT count(*) FROM baseline_outbox WHERE status='PENDING'") == 0)
        self.drain(p, "driftwatch-sink")
        required = ["driftwatch_ingestion_ack_duration_seconds", "driftwatch_processing_duration_seconds",
                    "driftwatch_collector_polls_total", "driftwatch_source_outbox_pending",
                    "driftwatch_dead_letters_pending", "driftwatch_alerts_fired_total", "driftwatch_retention_rows_pruned"]
        code, metrics = self.http(p, "/actuator/prometheus")
        require(code == 200 and all(m in metrics for m in required), "missing required live-source metrics")
        require("ingestion_id=" not in metrics and "event_id=" not in metrics, "unsafe metric identity labels")
        (self.out / "source-prometheus.txt").write_text(metrics)
        self.record("source_metrics", {"status": "PASSED", "required_meters": required, "identity_labels": False})
        self.stop_app(p, app)
        tables = ["raw_events", "quality_alerts", "alert_incidents", "schema_versions", "processed_receipts",
                  "ingestion_receipts", "source_inbox", "source_outbox", "collector_state", "source_poll_runs",
                  "source_gaps", "baseline_outbox", "dead_letter_records", "dead_letter_replays", "metric_windows"]
        before = self.snapshot(p, tables)
        require(before["source_inbox"]["count"] > 0 and before["processed_receipts"]["count"] > 0 and
                before["quality_alerts"]["count"] > 0, "empty restore fixture")
        require(self.count(p, "SELECT count(*) FROM processed_receipts p WHERE NOT EXISTS"
            " (SELECT 1 FROM raw_events r WHERE r.ingestion_id=p.ingestion_id)") == 0, "receipt/raw mismatch")
        dump = self.dump(p, "candidate-db.dump")
        self.shutdown(p)
        r = self.project("restore", 18114)
        self.infra(r)
        self.restore(r, dump)
        after = self.snapshot(r, tables, "driftwatch_restore")
        require(before == after, "restored tables do not match source counts and contents")
        self.record("candidate_backup_restore", {"status": "PASSED", "source": before, "restored": after,
                    "fresh_project": r["name"], "fresh_volume": f"{r['name']}_pgdata", "backup": dump.name,
                    "comparison": "all row values and counts in 15 acceptance tables"})
        app = self.app(r, "restored-candidate", db="driftwatch_restore")
        self.ready(r)
        op = self.admin(r)
        code, status = self.http(r, "/api/v1/operations/retention", opener=op)
        require(code == 200 and status["unresolved_alerts"] > 0, "restored app cannot read evidence")
        before_alerts = self.table(r, "quality_alerts", "driftwatch_restore", "status<>'RESOLVED'")
        before_schema = self.table(r, "schema_versions", "driftwatch_restore")
        before_state = self.table(r, "collector_state", "driftwatch_restore")
        self.sql(r, "UPDATE raw_events SET received_at=now()-interval '60 days' WHERE source='backup_acceptance'",
                 "driftwatch_restore")
        code, response = self.http(r, "/api/v1/operations/retention/run", "POST", opener=op, csrf=True)
        require(code == 200 and response.get("status") == "completed" and
                response.get("pruned", {}).get("raw_events", 0) >= 4, "actual retention did not prune expired inputs")
        require(self.table(r, "quality_alerts", "driftwatch_restore", "status<>'RESOLVED'") == before_alerts,
                "retention changed unresolved alert evidence")
        require(self.table(r, "schema_versions", "driftwatch_restore") == before_schema and
                self.table(r, "collector_state", "driftwatch_restore") == before_state,
                "retention changed schema or checkpoints")
        self.record("protected_retention", {"status": "PASSED", "http": code, "result": response,
                    "unresolved_alerts_unchanged": len(before_alerts), "schema_and_collector_state_unchanged": True})
        code, metrics = self.http(r, "/actuator/prometheus", opener=op)
        # Process counters reset on restart; the complete meter surface was checked on the source.
        require(code == 200 and "ingestion_id=" not in metrics and "event_id=" not in metrics, "unsafe metric identity labels")
        require("driftwatch_retention_rows_pruned" in metrics, "missing actual retention metric")
        (self.out / "restored-prometheus.txt").write_text(metrics)
        self.record("restored_runtime", {"status": "PASSED", "readiness": 200,
                    "admin_mutation_csrf": 200, "prometheus_http": code,
                    "present_meters": [m for m in required if m in metrics], "identity_labels": False})
        self.stop_app(r, app)
        self.shutdown(r)

    def persistent_sink_outage(self, p):
        ingestion = str(uuid.uuid4())
        received = dt.datetime.now(dt.timezone.utc).isoformat()
        envelope = {"contract_version": 1, "ingestion_id": ingestion,
                    "event": self.event("sink-outage", "outage_acceptance", late=True),
                    "received_at": received, "origin": "REST", "mode": "LIVE",
                    "origin_reference": "controlled-recovery-drill", "replay_of": None}
        require(self.count(p, "SELECT count(*) FROM dead_letter_records WHERE stage='SINK'") == 0,
                "unexpected preexisting sink dead letters")
        self.verify_owned(f"{p['name']}-postgres-1", p["name"])
        self.compose(p, "stop", "postgres")
        self.kafka(p, "kafka-console-producer", "--topic", "raw-events-v1",
                   "--producer-property", "acks=all", input_bytes=(json.dumps(envelope) + "\n").encode())
        self.record("sink_outage_started", {"ingestion_id": ingestion, "transport": "direct Kafka envelope",
                    "broker_producer_exit": 0, "http_ingestion_claimed": False})
        output = self.kafka(p, "kafka-console-consumer", "--topic", "dead-letter-events-v1",
                            "--from-beginning", "--timeout-ms", "300000", "--max-messages", "1", timeout=330)
        messages = [json.loads(x) for x in output.stdout.decode().splitlines() if x.startswith("{")]
        require(len(messages) == 1 and (messages[0].get("ingestion_id") or messages[0].get("ingestionId")) == ingestion,
                "actual Kafka DLT does not preserve controlled ingestion identity")
        (self.out / "sink-outage-kafka-dlt.json").write_text(json.dumps(messages[0], indent=2))
        self.compose(p, "start", "postgres")
        self.wait("PostgreSQL recovery", lambda: self.docker_json("inspect", f"{p['name']}-postgres-1")[0][
            "State"].get("Health", {}).get("Status") == "healthy")
        self.ready(p)
        self.wait("durable DLT projection", lambda: self.count(p,
            "SELECT count(*) FROM dead_letter_records WHERE stage='SINK' AND ingestion_id=" + quote(ingestion)) == 1)
        dlid = self.count(p, "SELECT id FROM dead_letter_records WHERE ingestion_id=" + quote(ingestion))
        require(self.count(p, "SELECT count(*) FROM processed_receipts WHERE ingestion_id=" + quote(ingestion)) == 0,
                "failed sink committed a partial receipt")
        op = self.admin(p)
        results = []
        for _ in range(2):
            code, result = self.http(p, f"/api/v1/dead-letters/{dlid}/replay", "POST", opener=op, csrf=True)
            require(code == 202 and result.get("outcome") == "PUBLISHED", "protected replay did not succeed")
            results.append(result)
            self.wait("replayed sink receipt", lambda: self.count(p,
                "SELECT count(*) FROM processed_receipts WHERE ingestion_id=" + quote(ingestion)) == 1)
            self.drain(p, "driftwatch-sink")
        require(self.count(p, "SELECT count(*) FROM raw_events WHERE ingestion_id=" + quote(ingestion)) == 1,
                "replay duplicated or lost raw event")
        require(self.count(p, f"SELECT count(*) FROM dead_letter_replays WHERE dead_letter_id={dlid}") == 2,
                "replay attempt history mismatch")
        self.record("persistent_sink_outage_and_replay", {"status": "PASSED", "ingestion_id": ingestion,
                    "dead_letter_id": dlid, "broker_dlt": "sink-outage-kafka-dlt.json",
                    "receipt_before_replay": 0, "receipt_after_two_replays": 1,
                    "raw_after_two_replays": 1, "protected_replays": results})

    def shutdown(self, p):
        require(not p["apps"], "stop owned apps before infrastructure cleanup")
        ids = self.command(["docker", "ps", "-aq", "--filter", f"label=com.docker.compose.project={p['name']}"]).stdout.decode().split()
        for cid in ids:
            self.verify_owned(cid, p["name"])
        self.compose(p, "down", "--volumes", timeout=120)

    def execute(self):
        require(self.docker_json("image", "inspect", self.image)[0]["Id"] == self.image, "missing frozen image")
        self.legacy_image = self.docker_json("image", "inspect", self.args.legacy_image)[0]["Id"]
        self.report["legacy_image_id"] = self.legacy_image
        self.upgrade()
        self.backup_restore()
        files = {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in self.out.iterdir()
                 if p.is_file() and p.name != "recovery-report.json"}
        for p in self.out.iterdir():
            if p.is_file():
                data = p.read_bytes()
                require(not any(x.encode() in data for x in self.credentials), "secret in evidence")
        self.report.update(status="PASSED", artifacts=files, finished_at=dt.datetime.now(dt.timezone.utc).isoformat())
        self.save()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", required=True)
    parser.add_argument("--legacy-image", required=True)
    parser.add_argument("--out", required=True)
    args = parser.parse_args()
    drill = Drill(args)
    try:
        drill.execute()
    except Exception as e:
        error = str(e)
        for secret in drill.credentials:
            error = error.replace(secret, "[REDACTED]")
        drill.report.update(status="FAILED", error=error, finished_at=dt.datetime.now(dt.timezone.utc).isoformat())
        drill.save()
        print(error, flush=True)
        return 1
    print(str(drill.out / "recovery-report.json"), flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
