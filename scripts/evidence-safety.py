#!/usr/bin/env python3
"""Credential redaction and fail-closed scan of a release evidence staging directory."""
import argparse
import io
import json
from pathlib import Path
import re
import zipfile

PATTERN = re.compile(r'(?:gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|AKIA[A-Z0-9]{16}|-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----)')
PATTERN_BYTES = re.compile(rb'(?:gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|AKIA[A-Z0-9]{16}|-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----\s+[A-Za-z0-9+/=\r\n]{64,}-----END (?:RSA |EC |OPENSSH )?PRIVATE KEY-----)')
MAX_ARCHIVE_ENTRIES = 100_000
MAX_ARCHIVE_MEMBER_BYTES = 64 * 1024 * 1024
MAX_ARCHIVE_EXPANDED_BYTES = 1_000_000_000
MAX_ARCHIVE_DEPTH = 4

def allowed_evidence(path):
    path = Path(path)
    if path.suffix not in {'.json', '.jsonl', '.txt', '.md', '.log', '.png', '.svg', '.xml', '.yml', '.yaml', '.err', '.jar'} or any(word in path.name.lower() for word in ['credential', 'auth.json', '.env']):
        raise ValueError(f'unsafe evidence file: {path.name}')

def scan_jar(data, path, secrets, budget=None, depth=0):
    """Scan an application JAR and nested ZIP/JAR dependencies without extracting them."""
    if depth > MAX_ARCHIVE_DEPTH:
        raise ValueError(f'evidence archive nesting limit exceeded: {path.name}')
    if budget is None:
        budget = {'entries': 0, 'expanded_bytes': 0}
    try:
        archive = zipfile.ZipFile(io.BytesIO(data))
    except zipfile.BadZipFile as error:
        raise ValueError(f'invalid JAR evidence: {path.name}') from error
    with archive:
        members = archive.infolist()
        budget['entries'] += len(members)
        if budget['entries'] > MAX_ARCHIVE_ENTRIES:
            raise ValueError(f'evidence archive entry limit exceeded: {path.name}')
        for member in members:
            if member.is_dir():
                continue
            lowered = member.filename.lower().replace('\\', '/')
            basename = lowered.rsplit('/', 1)[-1]
            if lowered.endswith('.env') or basename in {'auth.json', 'credentials.json'}:
                raise ValueError(f'private deployment file in JAR evidence: {member.filename}')
            if member.file_size > MAX_ARCHIVE_MEMBER_BYTES:
                raise ValueError(f'evidence archive member is too large: {member.filename}')
            budget['expanded_bytes'] += member.file_size
            if budget['expanded_bytes'] > MAX_ARCHIVE_EXPANDED_BYTES:
                raise ValueError(f'evidence archive expansion limit exceeded: {path.name}')
            content = archive.read(member)
            if PATTERN_BYTES.search(content) or any(value.encode() in content for value in secrets):
                raise ValueError(f'credential-like data in JAR evidence: {member.filename}')
            if member.filename.lower().endswith(('.jar', '.zip')):
                scan_jar(content, path, secrets, budget, depth + 1)

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
        if path.suffix == '.jar':
            scan_jar(original, path, secrets)
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
