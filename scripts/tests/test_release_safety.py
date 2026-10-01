"""Focused regressions for release false positives; no containers or networks."""
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest

SCRIPTS = Path(__file__).resolve().parents[1]

def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    mod = importlib.util.module_from_spec(spec); spec.loader.exec_module(mod)
    return mod

ctx = load('context', SCRIPTS / 'release-context.py')
assets = load('assets', SCRIPTS / 'public-assets.py')
runtime = load('runtime', SCRIPTS / 'phases/release-runtime.py')
safety = load('safety', SCRIPTS / 'evidence-safety.py')
acceptance = load('acceptance', SCRIPTS / 'acceptance.py')

class ReleaseSafety(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name).resolve()
        self.original_root = ctx.ROOT
        ctx.ROOT = self.root
        subprocess.run(['git', 'init', '-q', str(self.root)], check=True)
        self.write('src/app.txt', 'candidate')
        self.write('pom.xml', 'lock')
        self.write('.mvn/wrapper/maven-wrapper.properties', 'fixed')
        self.write('config.json', '{}')
        self.git('add', '.')
        self.git('-c', 'user.name=Test', '-c', 'user.email=test@example.invalid', 'commit', '-qm', 'candidate')
        sha = self.git('rev-parse', 'HEAD')
        self.freeze = {'git_sha':sha, 'source_tree_hash':ctx.tree_hash(ctx.ROOTS), 'config_hash':ctx.tree_hash(['config.json']), 'config_files':['config.json'], 'dependency_lock_hash':ctx.tree_hash(['pom.xml','.mvn/wrapper/maven-wrapper.properties']), 'image_id':'sha256:frozen', 'content_identity':{'jar_content_hash':'frozen'}}
        self.write('freeze.json', self.freeze)
        gates = {}
        for gate_id, name in ctx.GATES.items():
            path = 'gates/' + gate_id + '.json'
            self.write(path, {'id':name,'status':'PASSED','exit_code':0,'git_sha':sha,'evidence_paths':['report.json']})
            gates[gate_id] = path
        self.write('report.json', {'run_id':'accepted','measured_seconds':86401,'problems':[]})
        self.write('.execution/soak/accepted/state.json', {'run_id':'accepted','status':'PASSED','image':{'image_id':'sha256:frozen'}})
        self.write('.execution/soak/accepted/result.json', {'status':'PASSED'})
        self.context = {**{k:self.freeze[k] for k in ['source_tree_hash','config_hash','dependency_lock_hash','image_id','content_identity']},'freeze_manifest':'freeze.json','candidate_application_sha':sha,'gates':gates,'soak':{'run_id':'accepted','report':'report.json'}}
        self.write('context.json', self.context)

    def tearDown(self):
        ctx.ROOT = self.original_root
        self.temp.cleanup()

    def git(self, *args):
        return subprocess.run(['git','-C',str(self.root),*args],check=True,capture_output=True,text=True).stdout.strip()

    def write(self, path, data):
        file = self.root / path; file.parent.mkdir(parents=True, exist_ok=True)
        file.write_text(json.dumps(data) if isinstance(data, dict) else data)

    def validate(self): return ctx.validate('context.json')

    def test_complete_bound_evidence_passes(self): self.validate()
    def test_invalid_json_fails(self):
        self.write('gates/G16.json', '{invalid')
        with self.assertRaises(ValueError): self.validate()
    def test_historical_mtime_cannot_override_explicit_gate(self):
        self.write('history/gate.json', {'id':'SOAK','status':'FAILED'})
        self.validate()
    def test_missing_gate_fails(self):
        (self.root/'gates/G02.json').unlink()
        with self.assertRaises(ValueError): self.validate()
    def test_old_application_gate_fails(self):
        self.write('src/app.txt', 'old')
        self.git('add', '.'); self.git('-c','user.name=Test','-c','user.email=test@example.invalid','commit','-qm','different')
        old = self.git('rev-parse','HEAD')
        self.write('src/app.txt','candidate')
        self.write('gates/G02.json', {'id':'COMPOSE','status':'PASSED','exit_code':0,'git_sha':old,'evidence_paths':['report.json']})
        with self.assertRaises(ValueError): self.validate()
    def test_changed_application_fails(self):
        self.write('src/app.txt','changed')
        with self.assertRaises(ValueError): self.validate()
    def test_wrong_freeze_fails(self):
        self.freeze['image_id']='wrong'; self.write('freeze.json',self.freeze)
        with self.assertRaises(ValueError): self.validate()
    def test_wrong_run_fails(self):
        self.write('report.json', {'run_id':'historical','measured_seconds':86401,'problems':[]})
        with self.assertRaises(ValueError): self.validate()
    def test_running_window_fails(self):
        self.write('.execution/soak/accepted/state.json', {'run_id':'accepted','status':'RUNNING','image':{'image_id':'sha256:frozen'}})
        with self.assertRaises(ValueError): self.validate()
    def test_short_window_fails(self):
        self.write('report.json', {'run_id':'accepted','measured_seconds':86399,'problems':[]})
        with self.assertRaises(ValueError): self.validate()
    def test_failed_report_fails(self):
        self.write('report.json', {'run_id':'accepted','measured_seconds':86401,'problems':['lost events']})
        with self.assertRaises(ValueError): self.validate()
    def test_asset_hash_missing_checksum_corruption(self):
        self.write('bundle.tar.gz','bytes')
        sha=hashlib.sha256(b'bytes').hexdigest()
        self.write('checksums.txt', f'{sha}  bundle.tar.gz\n')
        checks=hashlib.sha256((self.root/'checksums.txt').read_bytes()).hexdigest()
        expected={'bundle.tar.gz':sha,'checksums.txt':checks}
        assets.verify_assets(self.root, expected)
        self.write('bundle.tar.gz','corrupted')
        with self.assertRaises(ValueError): assets.verify_assets(self.root,expected)
        (self.root/'bundle.tar.gz').unlink()
        with self.assertRaises(FileNotFoundError): assets.verify_assets(self.root,expected)
    def test_202_without_specific_receipt_fails(self):
        with self.assertRaises(ValueError): runtime.require_receipt({'raw':0,'processed':0,'receipt':1,'ingestion_id':'accepted'},'accepted')
    def test_other_events_cannot_satisfy_receipt(self):
        with self.assertRaises(ValueError): runtime.require_receipt({'raw':1,'processed':1,'receipt':1,'ingestion_id':'other'},'accepted')

    def test_evidence_env_and_auth_files_rejected(self):
        for name in ['actual.env', 'auth.json', 'credentials.json']:
            with self.assertRaises(ValueError): safety.allowed_evidence(name)
    def test_known_credential_is_redacted(self):
        stage=self.root/'stage';stage.mkdir()
        (stage/'record.json').write_text('{"token":"real-secret-value"}')
        safety.redact_and_scan(stage,{'real-secret-value'})
        self.assertNotIn('real-secret-value',(stage/'record.json').read_text())
    def test_unknown_credential_blocks_evidence(self):
        stage=self.root/'stage';stage.mkdir()
        (stage/'record.json').write_text(json.dumps({'leaked':'ghp_'+'abcdefghijklmnopqrstuvwxyz012345'}))
        with self.assertRaises(ValueError): safety.redact_and_scan(stage,set())
    def test_invalid_archive_blocks_installation(self):
        self.write('bad.tar.gz','not an archive')
        import tarfile
        with self.assertRaises(tarfile.ReadError): assets.extract_bundle(self.root/'bad.tar.gz',self.root/'install')

    def test_existing_login_is_not_a_download_credential(self):
        from unittest.mock import patch
        with patch.dict('os.environ', {'GH_TOKEN':'existing-login-token'}), patch.object(assets.subprocess,'run') as invoked:
            assets.anonymous_download('https://github.com/owner/repo/releases/download/v1/bundle',self.root/'bundle')
            command=invoked.call_args.args[0]
            self.assertEqual(command[:2],['curl','-q'])
            self.assertFalse(any(option in command for option in ['--user','--netrc','--header','--cookie']))
            self.assertNotIn('existing-login-token',' '.join(command))
    def test_bundle_path_escape_is_rejected(self):
        import io,tarfile
        archive=self.root/'escape.tar.gz'
        with tarfile.open(archive,'w:gz') as handle:
            info=tarfile.TarInfo('../escape');info.size=1;handle.addfile(info,io.BytesIO(b'x'))
        with self.assertRaises(ValueError): assets.extract_bundle(archive,self.root/'install')

    def test_unknown_consumer_lag_is_not_zero(self):
        from unittest.mock import patch
        import types
        with patch.object(acceptance,'compose',return_value=types.SimpleNamespace(returncode=0,stdout='GROUP TOPIC PARTITION CURRENT END LAG\n',stderr='')):
            self.assertEqual(acceptance.consumer_lag_total('project','env')[0],-1)
    def test_empty_dlt_partition_has_proven_zero_lag(self):
        from unittest.mock import patch
        import types
        def described(project,env,*args):
            group=args[-1]
            return types.SimpleNamespace(returncode=0,stdout=f'{group} topic 0 - 0 - consumer host client\n',stderr='')
        with patch.object(acceptance,'compose',side_effect=described):
            total,detail=acceptance.consumer_lag_total('project','env')
            self.assertEqual(total,0);self.assertEqual(len(detail),3)

    def run_formal_soak(self, ledger_override=None, result_status='PASSED', finished_age=30):
        import contextlib,datetime,io,types
        from unittest.mock import patch
        end=datetime.datetime.now(datetime.timezone.utc)-datetime.timedelta(seconds=finished_age)
        start=end-datetime.timedelta(seconds=86400)
        stamp=lambda t:t.strftime('%Y-%m-%dT%H:%M:%SZ')
        directory=self.root/'formal-run';directory.mkdir()
        state={'run_id':'formal','status':'PASSED','project':'test','env_file':'unused','started_utc':stamp(start),'duration_seconds':86400,'image':{'image_id':'sha256:frozen','container':'accepted-container'},'git_sha':self.context['candidate_application_sha'],'planned_faults':[{'action':action,'at_seconds':when} for action,when in [('app-restart',7200),('kafka-stop',28800),('db-stop',57600)]]}
        (directory/'state.json').write_text(json.dumps(state))
        (directory/'result.json').write_text(json.dumps({'status':result_status,'measured_seconds':86401,'finished_utc':stamp(end)}))
        faults=[{'action':f['action'],'utc':stamp(start+datetime.timedelta(seconds=f['at_seconds'])),'status':'completed','outage_seconds':30,'readiness_recovery_seconds':10} for f in state['planned_faults']]
        (directory/'faults.jsonl').write_text('\n'.join(json.dumps(f) for f in faults))
        samples=[{'utc':stamp(start+datetime.timedelta(seconds=i*120)),'monotonic':1000000+i*120,'readiness':200,'liveness':200,'recent_probe':200,'container_stats':['test-app-1 0.0% 600MiB / 18GiB']} for i in range(721)]
        ledger={'accepted':0,'unconfirmed':0,'processed':20,'raw':20,'dlt_open':0,'pending_outbox':0,'accepted_without_processed':0,'processed_without_raw':0,'source_without_processed':0,'raw_without_processed':0}
        if ledger_override is not None:ledger=ledger_override
        source={'github_distinct_events':20,'github_live_events':1}
        with patch.object(acceptance,'run_dir',return_value=str(directory)), patch.object(acceptance,'continuity_report',return_value={'continuity_valid':True,'samples':721,'max_gap_seconds':120}), patch.object(acceptance,'read_samples',return_value=samples), patch.object(acceptance,'identify_image',return_value={'image_id':'sha256:frozen','container':'accepted-container'}), patch.object(acceptance,'psql_json',side_effect=[source,ledger]), patch.object(acceptance,'consumer_lag_total',return_value=(0,[])), patch.object(acceptance,'container_health',return_value={'containers':{},'problems':[]}), contextlib.redirect_stdout(io.StringIO()):
            code=acceptance.cmd_soak_report(types.SimpleNamespace(run_id='formal',out=str(directory/'report')))
        return code,json.loads((directory/'report/soak-report.json').read_text())
    def test_formal_soak_pass_requires_deadline_evidence(self):
        code,report=self.run_formal_soak()
        self.assertEqual(code,0,report['problems']);self.assertEqual(report['drain_confirmation']['lag_total'],0)
    def test_missing_ledger_cannot_be_zero(self):
        code,report=self.run_formal_soak(ledger_override={})
        self.assertEqual(code,1);self.assertTrue(any('ledger is missing' in p for p in report['problems']))
    def test_failed_runner_cannot_pass_report(self):
        code,report=self.run_formal_soak(result_status='FAILED')
        self.assertEqual(code,1);self.assertIn('runner has not completed successfully',report['problems'])
    def test_late_report_cannot_infer_timely_drain(self):
        code,report=self.run_formal_soak(finished_age=601)
        self.assertEqual(code,1);self.assertTrue(any('ten minutes' in p for p in report['problems']))

if __name__ == '__main__': unittest.main()
