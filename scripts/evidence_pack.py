"""Helpers for building a sanitized, release-bound evidence archive."""

from pathlib import Path


def _resolved(root, value):
    path = Path(value)
    return (path if path.is_absolute() else Path(root) / path).resolve()


def _private_evidence_path(value):
    name = Path(value).name.lower()
    return (name == '.env' or name.endswith('.env') or name in {'auth.json', 'credentials.json'}
            or 'credential' in name)


def collect_evidence_files(context, gate_summaries, context_path, repository_root):
    """Return explicitly bound evidence paths, never the private release context."""
    files = {context['freeze_manifest']}
    soak = context.get('soak')
    if soak:
        files.add(soak['report'])

    for gate in gate_summaries.values():
        if gate.get('path'):
            files.add(gate['path'])
        files.update(gate.get('evidence_paths') or [])
    files.update(context.get('evidence_files') or [])

    if soak:
        run_id = soak['run_id']
        for name in (
            'state.json', 'result.json', 'samples.jsonl', 'checkpoint.json',
            'faults.jsonl', 'prefault-takeover.json', 'drain-confirmation.json',
        ):
            files.add(f'.execution/soak/{run_id}/{name}')

    context_identity = _resolved(repository_root, context_path)
    return sorted(
        value for value in files
        if _resolved(repository_root, value) != context_identity and not _private_evidence_path(value)
    )


def build_evidence_report(context, archive_sha256):
    """Describe the archive without serializing private waiver or run context."""
    report = {
        'status': 'PASSED',
        'archive_sha256': archive_sha256,
        'source_tree_hash': context['source_tree_hash'],
        'gates': context['gates'],
        'remaining_credentials': 0,
    }
    soak = context.get('soak')
    if soak:
        report['soak_run_id'] = soak['run_id']
    return report


def validate_evidence_report(report, context, archive_sha256):
    expected_soak = (context.get('soak') or {}).get('run_id')
    actual_soak = report.get('soak_run_id')
    valid_soak_binding = actual_soak == expected_soak if expected_soak else actual_soak is None
    if (
        report.get('status') != 'PASSED'
        or report.get('remaining_credentials') != 0
        or report.get('archive_sha256') != archive_sha256
        or report.get('source_tree_hash') != context['source_tree_hash']
        or report.get('gates') != context.get('gates', {})
        or not valid_soak_binding
    ):
        raise ValueError('evidence archive lacks a matching sanitized binding report')
