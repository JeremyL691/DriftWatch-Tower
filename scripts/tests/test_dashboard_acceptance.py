import json
from pathlib import Path
import subprocess
import tempfile
import unittest

CHECKER = Path(__file__).resolve().parents[1] / 'phases' / 'check-dashboard.py'
VIEWPORTS = ('320', '768', '1024', '1440')
THEMES = ('dark', 'light')


def page(viewport, theme, state=None, badge=False, violations=None):
    result = {
        'viewport': viewport,
        'theme': theme,
        'status': 200,
        'consoleErrors': [],
        'horizontalOverflow': False,
        'keyboard': {'focusable': True, 'outlineWidth': '2px'},
        'accessibility': {'ran': True, 'violations': violations or []},
        'wsStatus': {'state': 'connected', 'label': 'Live'},
    }
    if state:
        result.update(
            state=state,
            unhealthySources=[{'source': 'demo-source', 'status': 'UNHEALTHY'}],
            unhealthyBadge={
                'present': badge,
                'source': 'demo-source' if badge else None,
                'labels': ['UNHEALTHY'] if badge else [],
            },
        )
    return result


class DashboardAcceptanceTests(unittest.TestCase):
    def run_checker(self, report):
        with tempfile.TemporaryDirectory() as directory:
            report_path = Path(directory) / 'report.json'
            summary_path = Path(directory) / 'summary.json'
            report_path.write_text(json.dumps(report))
            result = subprocess.run(
                ['python3', str(CHECKER), '--report', str(report_path), '--out', str(summary_path)],
                capture_output=True,
                text=True,
            )
            return result.returncode, json.loads(summary_path.read_text())

    def complete_report(self):
        pages = [page(v, t) for v in VIEWPORTS for t in THEMES]
        pages.extend(page(v, t, 'UNHEALTHY_SOURCE', True) for v in VIEWPORTS for t in THEMES)
        return {'pages': pages, 'actions': {'resolved': True}}

    def test_rejects_dashboard_without_post_refresh_unhealthy_captures(self):
        report = {'pages': [page(v, t) for v in VIEWPORTS for t in THEMES], 'actions': {'resolved': True}}
        code, summary = self.run_checker(report)
        self.assertEqual(code, 1)
        self.assertEqual(summary['unhealthy_state_captures'], 0)

    def test_rejects_unhealthy_state_missing_one_theme_and_viewport(self):
        report = self.complete_report()
        report['pages'] = [p for p in report['pages'] if not (p.get('state') == 'UNHEALTHY_SOURCE' and p['viewport'] == '1440' and p['theme'] == 'light')]
        code, summary = self.run_checker(report)
        self.assertEqual(code, 1)
        self.assertTrue(any('1440-light unhealthy state' in problem for problem in summary['problems']))

    def test_rejects_unhealthy_state_without_rendered_badge(self):
        report = self.complete_report()
        next(p for p in report['pages'] if p.get('state') == 'UNHEALTHY_SOURCE')['unhealthyBadge']['present'] = False
        code, summary = self.run_checker(report)
        self.assertEqual(code, 1)
        self.assertTrue(any('rendered unhealthy badge is missing' in problem for problem in summary['problems']))

    def test_rejects_badge_not_bound_to_an_observed_unhealthy_source(self):
        report = self.complete_report()
        page = next(p for p in report['pages'] if p.get('state') == 'UNHEALTHY_SOURCE')
        page['unhealthyBadge']['source'] = 'other-source'
        code, summary = self.run_checker(report)
        self.assertEqual(code, 1)
        self.assertTrue(any('not bound to an observed unhealthy source' in problem for problem in summary['problems']))

    def test_accepts_all_real_state_capture_slots_when_other_checks_pass(self):
        code, summary = self.run_checker(self.complete_report())
        self.assertEqual(code, 0, summary['problems'])
        self.assertEqual(summary['unhealthy_state_captures'], 8)

    def test_rejects_reports_that_never_exercised_protected_actions(self):
        report = self.complete_report()
        report['actions'] = {}
        code, summary = self.run_checker(report)
        self.assertEqual(code, 1)
        self.assertTrue(any('protected dashboard actions were not completed' in problem for problem in summary['problems']))


if __name__ == '__main__':
    unittest.main()
