#!/usr/bin/env python3
"""Fail-closed release evidence binding. No directory timestamp selection."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
ROOTS = ['src', 'pom.xml', 'Dockerfile', 'docker-compose.yml', 'docker-compose.dev.yml', '.mvn']
GATES = dict(zip(['G00', 'G02', 'G03', 'G04', 'G05', 'G06', 'G07', 'G08', 'G09', 'G10', 'G11', 'G12', 'G13', 'G14', 'G15', 'G16'], ['UNIT', 'COMPOSE', 'PHASE-P2', 'PHASE-P2', 'PHASE-P3', 'PHASE-P3', 'PHASE-P3', 'PHASE-P4', 'PHASE-P4', 'PHASE-P5', 'PHASE-P5b', 'PHASE-P5C', 'PHASE-P6', 'LOAD', 'PACKAGE', 'SOAK']))
WAIVER_STATUS = 'WAIVED_BY_USER'

def file_path(value):
    path = (ROOT / value).resolve()
    if not path.is_relative_to(ROOT) or not path.is_file():
        raise ValueError(f'missing or unsafe evidence file: {value}')
    return path

def atomic_json(path, data):
    path = Path(path)
    import os
    temporary = path.with_name(path.name + f'.tmp-{os.getpid()}')
    try:
        temporary.write_text(json.dumps(data, indent=2))
        temporary.replace(path)
    finally:
        temporary.unlink(missing_ok=True)

def read(value):
    data = json.loads(file_path(value).read_text())
    if not isinstance(data, dict):
        raise ValueError(f'expected JSON object: {value}')
    return data

def tree_hash(roots):
    entries = []
    for root in roots:
        path = ROOT / root
        files = [path] if path.is_file() else [p for p in path.rglob('*') if p.is_file() and 'target' not in p.relative_to(path).parts]
        entries.extend((str(p.relative_to(ROOT)), hashlib.sha256(p.read_bytes()).hexdigest()) for p in files)
    digest = hashlib.sha256()
    for name, sha in sorted(entries):
        digest.update(f'{name}\0{sha}\n'.encode())
    return digest.hexdigest()

def same_surface(sha, candidate):
    if not sha or subprocess.run(['git', '-C', str(ROOT), 'diff', '--quiet', sha, candidate, '--', *ROOTS], capture_output=True).returncode:
        raise ValueError(f'application surface differs or commit unavailable: {sha}')

def validate_recovery(c, freeze):
    """Require the actual operating drills in addition to phase-suite evidence."""
    if c.get('version') != 'v1.0.1' and 'recovery_drills' not in c:
        return
    path = c.get('recovery_drills', {}).get('report')
    if not path:
        raise ValueError('missing same-candidate recovery drill report')
    report = read(path)
    if report.get('status') != 'PASSED':
        raise ValueError('recovery drills are not PASSED')
    for key, expected in [('candidate_sha', c['candidate_application_sha']),
                          ('image_id', freeze['image_id']), ('source_tree_hash', freeze['source_tree_hash'])]:
        if report.get(key) != expected:
            raise ValueError(f'recovery candidate mismatch: {key}')
    required = ['legacy_backup_and_drain', 'historical_upgrade', 'legacy_backlog_bridge',
                'backup_based_rollback', 'persistent_sink_outage_and_replay',
                'candidate_backup_restore', 'protected_retention', 'source_metrics', 'restored_runtime']
    checks = report.get('checks', {})
    if any(checks.get(name, {}).get('status') != 'PASSED' for name in required):
        raise ValueError('missing required actual recovery checks')
    artifacts = report.get('artifacts', {})
    expected_files = {'legacy-db.dump', 'legacy-kafka.tar.gz', 'candidate-db.dump', 'executed-tool.py',
                      'bridge-0.json', 'bridge-1.json', 'sink-outage-kafka-dlt.json',
                      'source-prometheus.txt', 'restored-prometheus.txt', 'commands.log'}
    if not expected_files.issubset(artifacts):
        raise ValueError('missing required raw recovery artifacts')
    for name, digest in artifacts.items():
        if Path(name).name != name or not isinstance(digest, str) or len(digest) != 64:
            raise ValueError('unsafe recovery artifact or digest')
        target = file_path(str(file_path(path).parent.relative_to(ROOT) / name))
        if hashlib.sha256(target.read_bytes()).hexdigest() != digest:
            raise ValueError(f'recovery artifact hash mismatch: {name}')
    if report.get('tool_sha256') != artifacts['executed-tool.py']:
        raise ValueError('recovery executed tool differs from recorded hash')

def validate_g16_waiver(c, candidate):
    waivers = c.get('user_authorized_gate_waivers', {})
    if not isinstance(waivers, dict):
        raise ValueError('invalid user-authorized gate waiver map')
    waiver = waivers.get('G16')
    if waiver is None:
        return False
    if not isinstance(waiver, dict):
        raise ValueError('invalid G16 waiver record')
    if c.get('version') != 'v1.0.2':
        raise ValueError('G16 waiver is scoped to the v1.0.2 candidate only')
    if waiver.get('status') != WAIVER_STATUS or waiver.get('candidate_application_sha') != candidate:
        raise ValueError('invalid or incorrectly bound G16 waiver')
    if c.get('gates', {}).get('G16') is not None or c.get('soak') is not None:
        raise ValueError('a waived G16 must not be represented by a gate or soak report')
    return True

def validate(context, published=False):
    c = read(context)
    freeze = read(c['freeze_manifest'])
    for key in ['source_tree_hash', 'config_hash', 'dependency_lock_hash', 'image_id', 'content_identity']:
        if not freeze.get(key) or c.get(key) != freeze[key]:
            raise ValueError(f'freeze/context mismatch: {key}')
    candidate = c['candidate_application_sha']
    same_surface(freeze['git_sha'], candidate)
    for key, roots in [('source_tree_hash', ROOTS), ('config_hash', freeze['config_files']), ('dependency_lock_hash', ['pom.xml', '.mvn/wrapper/maven-wrapper.properties'])]:
        if tree_hash(roots) != freeze[key]:
            raise ValueError(f'working tree changed: {key}')
    validate_recovery(c, freeze)
    g16_waived = validate_g16_waiver(c, candidate)
    gate_paths = c.get('gates')
    if not isinstance(gate_paths, dict):
        raise ValueError('missing gate evidence map')
    required_gate_ids = set(GATES) - ({'G16'} if g16_waived else set())
    if set(gate_paths) != required_gate_ids:
        raise ValueError('gate evidence map is incomplete or contains unrecognized gates')
    summary = {}
    for gate_id, name in GATES.items():
        if gate_id == 'G16' and g16_waived:
            summary[gate_id] = {
                'id': name,
                'status': WAIVER_STATUS,
                'candidate_application_sha': candidate,
            }
            continue
        path = c['gates'][gate_id]
        gate = read(path)
        if gate.get('id') != name or gate.get('status') != 'PASSED' or gate.get('exit_code') != 0:
            raise ValueError(f'{gate_id}: not a valid PASSED {name} gate: {path}')
        same_surface(gate.get('git_sha'), candidate)
        if not gate.get('evidence_paths'):
            raise ValueError(f'{gate_id}: no evidence files')
        for evidence in gate['evidence_paths']:
            path = file_path(evidence)
            if path.suffix == '.json':
                json.loads(path.read_text())
        summary[gate_id] = {**gate, 'path': c['gates'][gate_id]}
    if not g16_waived:
        soak = c['soak']
        state = read(f'.execution/soak/{soak["run_id"]}/state.json')
        result = read(f'.execution/soak/{soak["run_id"]}/result.json')
        report = read(soak['report'])
        if state.get('run_id') != soak['run_id'] or report.get('run_id') != soak['run_id']:
            raise ValueError('wrong soak run')
        if state.get('image', {}).get('image_id') != freeze['image_id'] or state.get('status') != 'PASSED' or result.get('status') != 'PASSED':
            raise ValueError('soak not complete or image differs')
        if report.get('problems') != [] or report.get('measured_seconds', 0) < 86400:
            raise ValueError('G16 report did not pass a full 24-hour window')
        if file_path(soak['report']) not in [file_path(p) for p in summary['G16']['evidence_paths']]:
            raise ValueError('G16 does not bind the designated report')
    if published:
        same_surface(c['release_sha'], candidate)
        if not c.get('public_image_digest', '').startswith('sha256:') or len(c['public_image_digest']) != 71:
            raise ValueError('missing public digest')
        assets = c.get('assets', {})
        required = {'checksums.txt', 'release-manifest.json', c.get('bundle_asset'), c.get('sbom_asset'), c.get('evidence_asset')}
        if None in required or not required.issubset(assets) or any(len(v) != 64 or any(x not in '0123456789abcdef' for x in v) for v in assets.values()):
            raise ValueError('missing explicit required attachment hashes')
    return c, summary

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--context', required=True)
    parser.add_argument('--out')
    parser.add_argument('--published', action='store_true')
    args = parser.parse_args()
    try:
        _, gates = validate(args.context, args.published)
        payload = {'gates': gates, 'problems': []}
        code = 0
    except (ValueError, KeyError, OSError, TypeError) as error:
        payload = {'gates': {}, 'problems': [str(error)]}
        code = 1
    if args.out:
        Path(args.out).write_text(json.dumps(payload, indent=2))
    print(json.dumps(payload, indent=2))
    return code

if __name__ == '__main__':
    sys.exit(main())
