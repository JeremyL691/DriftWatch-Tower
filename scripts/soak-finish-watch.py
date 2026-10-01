#!/usr/bin/env python3
"""Wait for the existing runner, then capture the G16 drain deadline and report.

This process never starts/stops services, injects faults, publishes or cleans resources.
"""
import argparse
import datetime
import importlib.util
import json
from pathlib import Path
import subprocess
import time

ROOT=Path(__file__).resolve().parents[1]

def main():
    p=argparse.ArgumentParser();p.add_argument('--context',required=True);args=p.parse_args()
    context_path=(ROOT/args.context).resolve()
    context=json.loads(context_path.read_text());run=context['soak']['run_id']
    directory=ROOT/'.execution/soak'/run
    spec=importlib.util.spec_from_file_location('acceptance',ROOT/'scripts/acceptance.py')
    acceptance=importlib.util.module_from_spec(spec);spec.loader.exec_module(acceptance)
    while True:
        current=json.loads(context_path.read_text())
        if current['soak']['run_id']!=run or current['image_id']!=context['image_id']:
            raise ValueError('release context changed; watcher will not follow a different run implicitly')
        state=json.loads((directory/'state.json').read_text())
        if state['image']['image_id']!=context['image_id']:raise ValueError('run image differs')
        if state['status']!='RUNNING':break
        if not acceptance.runner_alive(str(directory)):
            # Let the runner finish its atomic result/state writes before concluding it was lost.
            time.sleep(2)
            state=json.loads((directory/'state.json').read_text())
            if state['status']=='RUNNING':raise ValueError('runner disappeared without completing')
            break
        time.sleep(30)
    if state['status']!='PASSED':raise ValueError('runner failed; no successful report will be inferred')
    result=json.loads((directory/'result.json').read_text())
    finished=datetime.datetime.fromisoformat(result['finished_utc'].replace('Z','+00:00'))
    report_path=ROOT/context['soak']['report'];report_path.parent.mkdir(parents=True,exist_ok=True)
    attempt=0
    while True:
        attempt+=1
        attempt_dir=report_path.parent/f'attempt-{attempt}'
        command=['python3',str(ROOT/'scripts/acceptance.py'),'soak-report','--run-id',run,'--out',str(attempt_dir)]
        completed=subprocess.run(command,cwd=ROOT,capture_output=True,text=True)
        attempt_dir.mkdir(exist_ok=True)
        (attempt_dir/'report-command.log').write_text(completed.stdout+completed.stderr)
        age=(datetime.datetime.now(datetime.timezone.utc)-finished).total_seconds()
        if completed.returncode==0:
            # Run once at the canonical location so evidence_paths name the actual formal report.
            final=subprocess.run(['python3',str(ROOT/'scripts/acceptance.py'),'soak-report','--run-id',run,'--out',str(report_path.parent)],cwd=ROOT,capture_output=True,text=True)
            (report_path.parent/'report-command.log').write_text(final.stdout+final.stderr)
            return final.returncode
        if age>=540:
            (report_path.parent/'watch-failure.json').write_text(json.dumps({'run_id':run,'status':'FAILED','attempts':attempt,'age_seconds':age,'reason':'formal G16 did not pass within drain deadline; inspect preserved attempts'},indent=2))
            return 1
        time.sleep(15)

if __name__=='__main__':raise SystemExit(main())
