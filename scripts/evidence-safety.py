#!/usr/bin/env python3
"""Credential redaction and fail-closed scan of a release evidence staging directory."""
import argparse
import json
from pathlib import Path
import re

PATTERN = re.compile(r'(?:gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|AKIA[A-Z0-9]{16}|-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----)')

def allowed_evidence(path):
    path = Path(path)
    if path.suffix not in {'.json', '.jsonl', '.txt', '.md', '.log', '.png', '.svg', '.xml', '.err'} or any(word in path.name.lower() for word in ['credential', 'auth.json', '.env']):
        raise ValueError(f'unsafe evidence file: {path.name}')

def redact_and_scan(stage, secrets):
    stage = Path(stage)
    changed = []
    for path in stage.rglob('*'):
        if not path.is_file(): continue
        allowed_evidence(path)
        original = path.read_bytes()
        if path.suffix == '.png':
            if any(value.encode() in original for value in secrets):
                raise ValueError(f'credential in binary evidence: {path.name}')
            continue
        text = original.decode('utf-8')
        for value in sorted(secrets, key=len, reverse=True):
            text = text.replace(value, '[redacted]')
        text = re.sub(r"(DWT_(?:POSTGRES_PASSWORD|ADMIN_PASSWORD|INGEST_TOKEN)=)[^\s\"']+", r'\1[redacted]', text)
        if PATTERN.search(text) or any(value in text for value in secrets):
            raise ValueError(f'credential-like data remains in evidence: {path.name}')
        if text != original.decode('utf-8'):
            path.write_text(text); changed.append(str(path.relative_to(stage)))
    return {'status':'PASSED', 'redacted_files':changed, 'remaining_credentials':0}

def main():
    p=argparse.ArgumentParser();p.add_argument('--stage',required=True);p.add_argument('--repo',required=True);args=p.parse_args()
    secrets=set()
    for path in Path(args.repo,'.execution').rglob('*.env'):
        for line in path.read_text().splitlines():
            key,_,value=line.strip().partition('=')
            if re.search(r'PASSWORD|TOKEN|SECRET|API_KEY',key) and len(value)>=8:secrets.add(value.strip('"\''))
    result=redact_and_scan(args.stage,secrets)
    Path(args.stage,'sanitization.json').write_text(json.dumps(result,indent=2))
    print(json.dumps(result))

if __name__=='__main__':main()
