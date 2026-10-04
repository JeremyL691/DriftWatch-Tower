#!/usr/bin/env python3
"""Download explicit release assets without GitHub/Docker authentication."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import tarfile
import sys


def verify_assets(directory, assets):
    directory = Path(directory)
    lines = (directory / 'checksums.txt').read_text().splitlines()
    checksums = {}
    for line in lines:
        match = re.fullmatch(r'([a-f0-9]{64})\s+\*?(?:\./)?(.+)', line)
        if not match:
            raise ValueError('malformed checksum line')
        sha, name = match.groups()
        if name in checksums:
            raise ValueError(f'duplicate checksum: {name}')
        checksums[name] = sha
    for name, expected in assets.items():
        if Path(name).name != name:
            raise ValueError(f'unsafe asset name: {name}')
        actual = hashlib.sha256((directory / name).read_bytes()).hexdigest()
        if actual != expected or (name != 'checksums.txt' and checksums.get(name) != actual):
            raise ValueError(f'asset checksum mismatch or missing coverage: {name}')
    return {'anonymous': True, 'credential_source': 'none: curl -q, no auth header, no cookies, no netrc', 'assets': assets}


def extract_bundle(bundle, directory):
    destination = Path(directory).resolve()
    with tarfile.open(bundle) as archive:
        for member in archive.getmembers():
            if member.issym() or member.islnk() or not (destination / member.name).resolve().is_relative_to(destination):
                raise ValueError(f'unsafe bundle member: {member.name}')
        archive.extractall(destination, filter='data')


def anonymous_download(url, target):
    # -q must be the first curl option: it suppresses existing user curlrc credentials.
    subprocess.run(['curl', '-q', '--fail', '--location', '--retry', '2', '--max-time', '300', '--proto', '=https', '--proto-redir', '=https', '--output', str(target), url], check=True, stdout=subprocess.DEVNULL)


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--context', required=True)
    p.add_argument('--release-url', required=True)
    p.add_argument('--out', required=True)
    args = p.parse_args()
    out = Path(args.out); out.mkdir(parents=True, exist_ok=True)
    context = json.loads(Path(args.context).read_text())
    if not re.fullmatch(r'https://github.com/[^/]+/[^/]+/releases/tag/v[^/]+', args.release_url):
        raise ValueError('unexpected release URL')
    base = args.release_url.replace('/releases/tag/', '/releases/download/')
    for name in context['assets']:
        if Path(name).name != name:
            raise ValueError('unsafe attachment name')
        anonymous_download(base + '/' + name, out / name)
    result = verify_assets(out, context['assets'])
    manifest = json.loads((out / 'release-manifest.json').read_text())
    for key in ['source_tree_hash', 'config_hash', 'dependency_lock_hash', 'content_identity']:
        if manifest.get(key) != context[key]: raise ValueError(f'published manifest mismatch: {key}')
    if manifest.get('release_commit') != context['release_sha'] or manifest['image']['published_digest'] != context['public_image_digest']:
        raise ValueError('published manifest commit/digest mismatch')
    (out / 'anonymous-download.json').write_text(json.dumps(result, indent=2))
    extract_bundle(out / context['bundle_asset'], out)
    return 0

if __name__ == '__main__':
    try:
        sys.exit(main())
    except Exception as error:
        print(f'public asset verification FAILED: {error}', file=sys.stderr)
        sys.exit(1)
