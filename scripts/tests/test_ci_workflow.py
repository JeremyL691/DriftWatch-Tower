import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
PHASES = ROOT / 'scripts' / 'phases'
sys.path.insert(0, str(PHASES))

spec = importlib.util.spec_from_file_location('check_release_ci', PHASES / 'check-release-ci.py')
check_release_ci = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = check_release_ci
spec.loader.exec_module(check_release_ci)

spec = importlib.util.spec_from_file_location('check_suite', PHASES / 'check-suite.py')
check_suite = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = check_suite
spec.loader.exec_module(check_suite)

JOBS = check_release_ci.REQUIRED_JOBS
SHA = 'a' * 40
CONTENT = 'b' * 64


def run(run_id=1, event='push', created='2026-10-08T00:00:00Z', status='completed',
        conclusion='success', head_sha=SHA, attempt=1):
    return {
        'id': run_id,
        'head_sha': head_sha,
        'event': event,
        'created_at': created,
        'status': status,
        'conclusion': conclusion,
        'run_attempt': attempt,
        'html_url': f'https://github.com/example/project/actions/runs/{run_id}',
    }


def jobs(attempt=1):
    return [
        {'id': index, 'name': name, 'status': 'completed', 'conclusion': 'success', 'run_attempt': attempt}
        for index, name in enumerate(JOBS, 1)
    ]


class CheckReleaseCiTests(unittest.TestCase):
    def test_rejects_non_stable_or_malformed_release_inputs(self):
        for values in [
            ('v1.0.2-rc1', SHA, SHA, CONTENT),
            ('v1.0', SHA, SHA, CONTENT),
            ('v1.0.3', 'a' * 39, 'a' * 39, CONTENT),
            ('v1.0.3', SHA, 'c' * 40, CONTENT),
            ('v1.0.3', SHA, SHA, 'not-a-digest'),
        ]:
            with self.subTest(values=values), self.assertRaises(ValueError):
                check_release_ci.require_candidate(*values)

    def test_selects_latest_candidate_run_across_push_and_manual_dispatch(self):
        latest = run(2, event='workflow_dispatch', created='2026-10-08T01:00:00Z')
        selected = check_release_ci.select_latest_run(
            [run(1), run(3, head_sha='c' * 40), latest, run(4, event='pull_request')], SHA)
        self.assertEqual(selected['id'], 2)

    def test_rejects_missing_or_unrelated_candidate_runs(self):
        for candidate_runs in [[], [run(1, head_sha='c' * 40)], [run(1, event='pull_request')]]:
            with self.subTest(candidate_runs=candidate_runs), self.assertRaises(ValueError):
                check_release_ci.select_latest_run(candidate_runs, SHA)

    def test_latest_failed_or_running_attempt_masks_an_older_green_run(self):
        for latest in [run(2, created='2026-10-08T01:00:00Z', conclusion='failure'),
                       run(2, created='2026-10-08T01:00:00Z', status='in_progress', conclusion=None)]:
            with self.subTest(latest=latest), self.assertRaisesRegex(ValueError, 'latest CI run'):
                check_release_ci.select_latest_run([run(1), latest], SHA)

    def test_latest_cancelled_or_skipped_run_masks_an_older_green_run(self):
        for latest in [run(2, created='2026-10-08T01:00:00Z', conclusion='cancelled'),
                       run(2, created='2026-10-08T01:00:00Z', conclusion='skipped')]:
            with self.subTest(latest=latest), self.assertRaisesRegex(ValueError, 'latest CI run'):
                check_release_ci.select_latest_run([run(1), latest], SHA)

    def test_run_id_breaks_same_second_timestamp_ties(self):
        with self.assertRaisesRegex(ValueError, 'latest CI run'):
            check_release_ci.select_latest_run(
                [run(7, created='2026-10-08T01:00:00Z'),
                 run(8, created='2026-10-08T01:00:00Z', conclusion='failure')], SHA)

    def test_requires_all_five_completed_successful_jobs_from_the_latest_attempt(self):
        self.assertEqual(set(check_release_ci.verify_required_jobs(jobs(), 1)), set(JOBS))
        for changed_job in [
            jobs()[:-1],
            [{**job, 'conclusion': 'failure'} if job['name'] == JOBS[0] else job for job in jobs()],
            [{**job, 'status': 'in_progress'} if job['name'] == JOBS[0] else job for job in jobs()],
            [{**job, 'conclusion': 'skipped'} if job['name'] == JOBS[0] else job for job in jobs()],
            jobs(attempt=0),
        ]:
            with self.subTest(changed_job=changed_job), self.assertRaises(ValueError):
                check_release_ci.verify_required_jobs(changed_job, 1)

    @patch.object(check_release_ci, 'gh_json')
    def test_remote_gate_queries_latest_run_and_its_jobs_before_passing(self, gh_json):
        gh_json.side_effect = [
            {'workflows': [{'id': 42, 'name': 'CI', 'path': '.github/workflows/ci.yml'}]},
            {'total_count': 1, 'workflow_runs': [run(7)]},
            {'total_count': 0, 'workflow_runs': []},
            {'total_count': len(JOBS), 'jobs': jobs()},
        ]
        result = check_release_ci.check_remote('example/project', SHA, 'v1.0.3', CONTENT, SHA)
        self.assertEqual(result['status'], 'PASSED')
        self.assertEqual(result['ci_run']['id'], 7)
        self.assertEqual(set(result['jobs']), set(JOBS))
        self.assertEqual(gh_json.call_count, 4)
        self.assertIn('workflows/42/runs', gh_json.call_args_list[1].args[0])

    @patch.object(check_release_ci, 'gh_json')
    def test_remote_gate_fails_closed_when_more_than_one_hundred_runs_exist(self, gh_json):
        gh_json.side_effect = [
            {'workflows': [{'id': 42, 'name': 'CI', 'path': '.github/workflows/ci.yml'}]},
            {'total_count': 100, 'workflow_runs': [run()]},
        ]
        with self.assertRaisesRegex(ValueError, 'too many candidate CI runs'):
            check_release_ci.check_remote('example/project', SHA, 'v1.0.3', CONTENT, SHA)

    @patch.object(check_release_ci, 'gh_json')
    def test_remote_gate_fails_closed_when_github_api_fails(self, gh_json):
        gh_json.side_effect = [
            {'workflows': [{'id': 42, 'name': 'CI', 'path': '.github/workflows/ci.yml'}]},
            ValueError('GitHub Actions API request failed'),
        ]
        with self.assertRaisesRegex(ValueError, 'API request failed'):
            check_release_ci.check_remote('example/project', SHA, 'v1.0.3', CONTENT, SHA)

    def test_suite_checker_rejects_failure_skip_and_missing_test_reports(self):
        with tempfile.TemporaryDirectory() as directory:
            reports = Path(directory) / 'reports'
            reports.mkdir()
            output = Path(directory) / 'summary.json'
            for counts in [('tests="1" failures="1" errors="0" skipped="0"', 'failure'),
                           ('tests="1" failures="0" errors="0" skipped="1"', 'skip')]:
                for path in reports.glob('*.xml'):
                    path.unlink()
                (reports / 'TEST-example.xml').write_text(
                    f'<testsuite name="example" {counts[0]}></testsuite>')
                with patch('sys.argv', ['check-suite', '--reports', str(reports), '--out', str(output)]):
                    self.assertEqual(check_suite.main(), 1, counts[1])
            for path in reports.glob('*.xml'):
                path.unlink()
            with patch('sys.argv', ['check-suite', '--reports', str(reports), '--out', str(output)]):
                self.assertEqual(check_suite.main(), 1, 'missing report')


if __name__ == '__main__':
    unittest.main()
