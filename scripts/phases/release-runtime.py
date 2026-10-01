#!/usr/bin/env python3
"""G17 live installation checks; never infer persistence from HTTP acceptance."""
import argparse
import base64
import datetime
import json
import os
from pathlib import Path
import subprocess
import time
import urllib.request
import urllib.error
import uuid

ROOT = Path(__file__).resolve().parents[2]

def require_receipt(row, identity):
    if row.get('ingestion_id') != identity or row.get('raw') != 1 or row.get('processed') != 1 or row.get('receipt') != 1:
        raise ValueError('specified ingestion did not persist exactly once in raw/processed/API receipt')


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--project', required=True)
    p.add_argument('--env-file', required=True)
    p.add_argument('--compose', required=True)
    p.add_argument('--base', required=True)
    p.add_argument('--out', required=True)
    args = p.parse_args()
    out = Path(args.out)
    env = {}
    for line in Path(args.env_file).read_text().splitlines():
        key, separator, value = line.partition('=')
        if separator and not key.startswith('#'): env[key] = value.strip('"\'')
    admin = 'Basic ' + base64.b64encode(f'{env["DWT_ADMIN_USERNAME"]}:{env["DWT_ADMIN_PASSWORD"]}'.encode()).decode()
    ingest = 'Bearer ' + env['DWT_INGEST_TOKEN']
    compose = ['docker', 'compose', '-p', args.project, '--env-file', args.env_file, '-f', args.compose]
    evidence = {'host_condition': 'fresh project/volumes/env, shared host Docker engine', 'synthetic_source': 'acceptance:release', 'problems': []}

    def run(*command):
        return subprocess.run(command, capture_output=True, text=True, check=True).stdout.strip()

    def db(sql):
        value = run(*compose, 'exec', '-T', 'postgres', 'sh', '-c', 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -tAc "$1"', 'sh', sql)
        return json.loads(value) if value else None

    def request(path, credential=None, payload=None, key=None):
        headers = {'Content-Type': 'application/json'}
        if credential: headers['Authorization'] = credential
        if key: headers['Idempotency-Key'] = key
        req = urllib.request.Request(args.base + path, headers=headers, data=json.dumps(payload).encode() if payload is not None else None)
        try:
            with urllib.request.urlopen(req, timeout=20) as response:
                data = response.read()
                return response.status, json.loads(data) if data else {}
        except urllib.error.HTTPError as error:
            return error.code, {}

    def wait_for(callback, timeout=180):
        end = time.monotonic() + timeout
        while True:
            result = callback()
            if result: return result
            if time.monotonic() > end: raise ValueError('live acceptance condition timed out')
            time.sleep(5)

    def receipt(identity):
        return db(f"select json_build_object('ingestion_id','{identity}','raw',(select count(*) from raw_events where ingestion_id='{identity}'),'processed',(select count(*) from processed_receipts where ingestion_id='{identity}'),'receipt',(select count(*) from ingestion_receipts where ingestion_id='{identity}'),'alerts',(select count(*) from quality_alerts where ingestion_id='{identity}'))")

    try:
        statuses = [request('/api/v1/events/recent', c)[0] for c in [None, admin, ingest]]
        evidence['authentication_statuses'] = dict(zip(['anonymous', 'admin', 'ingest_token_management'], statuses))
        if statuses[0] not in [401, 403] or statuses[1] != 200 or statuses[2] not in [401, 403]:
            raise ValueError('management authentication boundary failed')
        key = f'acceptance-{uuid.uuid4()}'
        payload = {'event_id': key, 'source': 'acceptance:release', 'event_type': 'ReleaseAcceptanceEvent', 'event_timestamp': datetime.datetime.now(datetime.timezone.utc).isoformat(), 'payload': {'amount': 1.0}}
        status, first = request('/api/v1/events', ingest, payload, key)
        identity = str(uuid.UUID(first['ingestion_id']))
        if status != 202: raise ValueError(f'ingest returned {status}')
        persisted = wait_for(lambda: (r if r['raw'] == 1 and r['processed'] == 1 and r['receipt'] == 1 else None) if (r := receipt(identity)) else None)
        require_receipt(persisted, identity)
        status, second = request('/api/v1/events', ingest, payload, key)
        time.sleep(5)
        repeated = receipt(identity)
        require_receipt(repeated, identity)
        if status != 202 or second.get('ingestion_id') != identity or repeated != persisted:
            raise ValueError('same-key retry changed identity or persistence side effects')
        evidence['ingestion'] = {'first': first, 'retry': second, 'before': persisted, 'after': repeated}
        status, api = request('/api/v1/events/' + identity, admin)
        if status != 200 or api.get('event_id') != key: raise ValueError('specified ingestion API lookup failed')
        evidence['ingestion_api'] = api
        official = wait_for(lambda: db("select row_to_json(t) from (select r.ingestion_id,r.event_id,i.github_event_id,i.poll_run_id,p.status poll_status,c.last_poll_success,c.etag_applied from raw_events r join source_inbox i using(ingestion_id) join source_poll_runs p on p.id=i.poll_run_id join collector_state c on c.source=i.source where r.origin='GITHUB' and c.last_poll_success is not null order by r.id desc limit 1) t"), timeout=600)
        status, api = request('/api/v1/events/' + official['ingestion_id'], admin)
        if status != 200 or api.get('origin') != 'GITHUB' or api.get('event_id') != official['github_event_id']:
            raise ValueError('official event ID did not correlate inbox/raw/API')
        evidence['official_event'] = {'database': official, 'api': api, 'collectors': request('/api/v1/sources/collectors', admin)[1]}
        before = db("select json_build_object('bootstrap',(select count(*) from source_poll_runs where mode='BOOTSTRAP'),'polls',(select count(*) from source_poll_runs),'checkpoint',(select last_poll_success from collector_state limit 1))")
        run(*compose, 'restart', 'app')
        wait_for(lambda: request('/actuator/health/readiness')[0] == 200)
        require_receipt(receipt(identity), identity)
        after = wait_for(lambda: (r if r['polls'] > before['polls'] and r['checkpoint'] and r['checkpoint'] > before['checkpoint'] else None) if (r := db("select json_build_object('bootstrap',(select count(*) from source_poll_runs where mode='BOOTSTRAP'),'polls',(select count(*) from source_poll_runs),'checkpoint',(select last_poll_success from collector_state limit 1))")) else None, timeout=600)
        if after['bootstrap'] != before['bootstrap']:
            raise ValueError('restart repeated bootstrap')
        evidence['restart'] = {'before': before, 'after': after, 'persisted': receipt(identity)}
        browser_env = {**os.environ, 'DWT_CAPTURE_USER': env['DWT_ADMIN_USERNAME'], 'DWT_CAPTURE_PASSWORD': env['DWT_ADMIN_PASSWORD']}
        subprocess.run(['node', str(ROOT / 'scripts/p53-capture.mjs'), '--out', str(out / 'browser'), '--label', 'release', '--base', args.base, '--themes', 'dark,light', '--exercise-actions', 'true'], env=browser_env, check=True)
        run('python3', str(ROOT / 'scripts/phases/check-dashboard.py'), '--report', str(out / 'browser/release-report.json'), '--out', str(out / 'browser-summary.json'))
        evidence['dashboard'] = json.loads((out / 'browser/release-report.json').read_text())
    except Exception as error:
        evidence['problems'].append(str(error))
    evidence['status'] = 'FAILED' if evidence['problems'] else 'PASSED'
    (out / 'runtime-summary.json').write_text(json.dumps(evidence, indent=2))
    print(json.dumps({'status': evidence['status'], 'problems': evidence['problems']}))
    return 1 if evidence['problems'] else 0

if __name__ == '__main__':
    raise SystemExit(main())
