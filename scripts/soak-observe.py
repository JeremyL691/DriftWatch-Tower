#!/usr/bin/env python3
"""Read-only live-run identity check plus durable same-run poller observations."""
import argparse
import datetime
import importlib.util
import json
from pathlib import Path
import shlex
import subprocess

ROOT=Path(__file__).resolve().parents[1]

def run(*args):return subprocess.check_output(args,cwd=ROOT,text=True,stderr=subprocess.PIPE).strip()

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--context',required=True);args=parser.parse_args()
    context_path=(ROOT/args.context).resolve();context=json.loads(context_path.read_text())
    live=[]
    for directory in (ROOT/'.execution/soak').iterdir():
        state_path=directory/'state.json';pid_path=directory/'runner.pid'
        if not state_path.is_file() or not pid_path.is_file():continue
        state=json.loads(state_path.read_text())
        if state.get('status')!='RUNNING':continue
        info=json.loads(pid_path.read_text());pid=str(int(info['pid']))
        try:
            started=run('ps','-o','lstart=','-p',pid)
            command=shlex.split(run('ps','-o','command=','-p',pid))
        except subprocess.CalledProcessError:continue
        if started!=info['process_start'] or '--run-id' not in command:continue
        if command[command.index('--run-id')+1]!=directory.name or not any(Path(word).name=='acceptance.py' for word in command):continue
        live.append((directory,state,info))
    if len(live)!=1:raise ValueError(f'expected exactly one effective live runner, found {len(live)}; inspect completed/context run before recovery')
    directory,state,info=live[0]
    if directory.name != context['soak']['run_id'] or state['image']['image_id'] != context['image_id']:
        raise ValueError('effective runner differs from designated context')
    last=json.loads((directory/'samples.jsonl').read_text().splitlines()[-1])
    now=datetime.datetime.now(datetime.timezone.utc)
    sampled=datetime.datetime.fromisoformat(last['utc'].replace('Z','+00:00'))
    if (now-sampled).total_seconds()>120:raise ValueError('live runner sample is stale')
    project=state['project'];env=state['env_file']
    cid=run('docker','compose','-p',project,'--env-file',env,'ps','-q','app')
    image=json.loads(run('docker','inspect',cid))[0]
    if cid != state['image']['container'] or image['Image']!=context['image_id'] or image['Config']['Labels'].get('com.docker.compose.project')!=project:
        raise ValueError('compose project/running image mismatch')
    poller=json.loads(run(str(ROOT/'scripts/poller-state.sh'),'--project',project,'--env-file',env))
    faults=[json.loads(line) for line in (directory/'faults.jsonl').read_text().splitlines() if line.strip()] if (directory/'faults.jsonl').exists() else []
    completed=[f for f in faults if f.get('status')=='completed']
    baseline=json.loads((directory/'prefault-takeover.json').read_text())
    bootstrap_before=baseline['polls_by_mode'].get('BOOTSTRAP',0)
    bootstrap_after=poller['polls_by_mode'].get('BOOTSTRAP',0)
    observation={'run_id':directory.name,'utc':now.isoformat(),'runner':info,'project':project,'image_id':image['Image'],'sample_age_seconds':(now-sampled).total_seconds(),'elapsed_seconds':last['elapsed_seconds'],'completed_faults':completed,'poller':poller,'comparison':{'bootstrap_before':bootstrap_before,'bootstrap_after':bootstrap_after,'bootstrap_unchanged':bootstrap_before==bootstrap_after,'live_before':baseline['live_events'],'live_after':poller['live_events'],'pending':poller['outbox_pending'],'failures':poller['collector_state']['consecutive_failures']}}
    name='observation-'+now.strftime('%Y%m%dT%H%M%SZ')+'.json'
    path=directory/name;path.write_text(json.dumps(observation,indent=2))
    evidence=str(path.relative_to(ROOT))
    if evidence not in context['evidence_files']:context['evidence_files'].append(evidence)
    temporary=context_path.with_suffix(".json.tmp")
    temporary.write_text(json.dumps(context,indent=2));temporary.replace(context_path)
    summary={**observation,"poller":{k:v for k,v in poller.items() if k!="event_identities"}}
    summary["snapshot"]=str(path.relative_to(ROOT))
    print(json.dumps(summary,indent=2))
    return 1 if bootstrap_after!=bootstrap_before else 0

if __name__=='__main__':raise SystemExit(main())
