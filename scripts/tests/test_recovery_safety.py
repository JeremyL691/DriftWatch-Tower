"""Recovery tooling failure guards; these tests do not access Docker or networks."""
import importlib.util
from pathlib import Path
from types import SimpleNamespace
import unittest
import json
import tempfile
import hashlib

path = Path(__file__).resolve().parents[1] / "phases/check-recovery.py"
spec = importlib.util.spec_from_file_location("recovery", path)
recovery = importlib.util.module_from_spec(spec)
spec.loader.exec_module(recovery)
context_path = path.parents[1] / "release-context.py"
context_spec = importlib.util.spec_from_file_location("recovery_context", context_path)
context = importlib.util.module_from_spec(context_spec)
context_spec.loader.exec_module(context)


class RecoverySafety(unittest.TestCase):
    def drain(self, rows):
        drill = recovery.Drill.__new__(recovery.Drill)
        drill.kafka = lambda *args, **kwargs: SimpleNamespace(stdout=rows.encode())
        drill.wait = lambda name, fn, **kwargs: fn()
        return drill.drain({}, "group")

    def test_empty_uncommitted_partition_has_no_backlog(self):
        self.assertTrue(self.drain("group raw-events 0 - 0 - consumer host client\n"
                                   "group raw-events 1 5 5 0 consumer host client\n"))

    def test_nonempty_uncommitted_partition_cannot_pass(self):
        self.assertFalse(self.drain("group raw-events 0 - 5 - consumer host client\n"))

    def test_actual_positive_lag_cannot_pass(self):
        self.assertFalse(self.drain("group raw-events 0 2 5 3 consumer host client\n"))

    def test_missing_or_other_group_cannot_pass(self):
        for rows in ["", "other raw-events 0 5 5 0 consumer host client\n"]:
            self.assertFalse(self.drain(rows))

    def test_ownership_requires_repo_and_config_not_only_project_name(self):
        drill = recovery.Drill.__new__(recovery.Drill)
        labels = {"com.docker.compose.project": "owned",
                  "com.docker.compose.project.working_dir": str(recovery.ROOT),
                  "com.docker.compose.project.config_files": str(recovery.ROOT / "docker-compose.yml")}
        drill.docker_json = lambda *args: [{"Config": {"Labels": dict(labels)}}]
        drill.verify_owned("container", "owned")
        for key in list(labels):
            original = labels[key]
            labels[key] = "unrelated"
            with self.assertRaises(RuntimeError):
                drill.verify_owned("container", "owned")
            labels[key] = original

    def test_sql_literal_quotes_cannot_escape(self):
        self.assertEqual(recovery.quote("an'id"), "'an''id'")

    def test_v101_requires_actual_recovery_report(self):
        with self.assertRaisesRegex(ValueError, "missing same-candidate"):
            context.validate_recovery({"version": "v1.0.1"}, {})

    def test_running_recovery_report_cannot_pass(self):
        original = context.ROOT
        with tempfile.TemporaryDirectory() as directory:
            try:
                context.ROOT = Path(directory).resolve()
                (context.ROOT / "report.json").write_text(json.dumps({"status": "RUNNING"}))
                with self.assertRaisesRegex(ValueError, "not PASSED"):
                    context.validate_recovery({"version": "v1.0.1", "recovery_drills": {"report": "report.json"}}, {})
            finally:
                context.ROOT = original

    def test_historical_context_has_no_retroactive_drill_requirement(self):
        context.validate_recovery({"version": "v1.0.0"}, {})

    def test_recovery_artifacts_and_candidate_are_bound(self):
        original = context.ROOT
        with tempfile.TemporaryDirectory() as directory:
            try:
                context.ROOT = Path(directory).resolve()
                names = ['legacy-db.dump', 'legacy-kafka.tar.gz', 'candidate-db.dump', 'executed-tool.py',
                         'bridge-0.json', 'bridge-1.json', 'sink-outage-kafka-dlt.json',
                         'source-prometheus.txt', 'restored-prometheus.txt', 'commands.log']
                hashes = {}
                for name in names:
                    data = name.encode()
                    (context.ROOT / name).write_bytes(data)
                    hashes[name] = hashlib.sha256(data).hexdigest()
                checks = ['legacy_backup_and_drain', 'historical_upgrade', 'legacy_backlog_bridge',
                          'backup_based_rollback', 'persistent_sink_outage_and_replay',
                          'candidate_backup_restore', 'protected_retention', 'source_metrics', 'restored_runtime']
                report = {'status': 'PASSED', 'candidate_sha': 'candidate', 'image_id': 'image',
                          'source_tree_hash': 'source', 'checks': {x: {'status': 'PASSED'} for x in checks},
                          'artifacts': hashes, 'tool_sha256': hashes['executed-tool.py']}
                c = {'version': 'v1.0.1', 'candidate_application_sha': 'candidate',
                     'recovery_drills': {'report': 'report.json'}}
                freeze = {'image_id': 'image', 'source_tree_hash': 'source'}
                file = context.ROOT / 'report.json'
                file.write_text(json.dumps(report))
                context.validate_recovery(c, freeze)
                (context.ROOT / 'candidate-db.dump').write_bytes(b'corrupted')
                with self.assertRaisesRegex(ValueError, 'hash mismatch'):
                    context.validate_recovery(c, freeze)
                (context.ROOT / 'candidate-db.dump').write_bytes(b'candidate-db.dump')
                report['candidate_sha'] = 'different'
                file.write_text(json.dumps(report))
                with self.assertRaisesRegex(ValueError, 'candidate mismatch'):
                    context.validate_recovery(c, freeze)
                report['candidate_sha'] = 'candidate'
                del report['checks']['backup_based_rollback']
                file.write_text(json.dumps(report))
                with self.assertRaisesRegex(ValueError, 'required actual recovery checks'):
                    context.validate_recovery(c, freeze)
            finally:
                context.ROOT = original


if __name__ == "__main__":
    unittest.main()
