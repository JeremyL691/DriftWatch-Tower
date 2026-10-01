#!/usr/bin/env python3
"""Asserts the release security evidence (gate G13).

A finding is never deleted or waived here: the gate fails, and the fix is an upgrade or a
documented upstream/reachability note kept alongside the raw scan output.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys

SEVERITIES = ("HIGH", "CRITICAL")


def load(path: str) -> dict:
    try:
        with open(path) as handle:
            return json.load(handle)
    except Exception:
        return {}


def vuln_findings(path: str) -> list[str]:
    data = load(path)
    findings = []
    for result in data.get("Results") or []:
        for vuln in result.get("Vulnerabilities") or []:
            if (vuln.get("Severity") or "").upper() in SEVERITIES:
                findings.append(
                    f"{vuln.get('VulnerabilityID')} {vuln.get('PkgName')} "
                    f"{vuln.get('InstalledVersion')} -> {vuln.get('FixedVersion') or 'no fix'}")
    return findings


def secret_findings(path: str) -> list[str]:
    data = load(path)
    findings = []
    for result in data.get("Results") or []:
        for secret in result.get("Secrets") or []:
            findings.append(f"{secret.get('RuleID')} in {result.get('Target')}:{secret.get('StartLine')}")
    return findings


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dir", required=True)
    parser.add_argument("--out", required=True)
    args = parser.parse_args()

    problems: list[str] = []
    summary: dict = {}

    version_text = ""
    version_path = os.path.join(args.dir, "trivy-version.txt")
    if os.path.exists(version_path):
        version_text = open(version_path).read().strip()
    if "Version" not in version_text:
        problems.append("scan tool version was not recorded")
    # The database identity lives in the tool banner (0.58 does not repeat it in the JSON
    # report). A scan whose database is missing or stale is not evidence of zero findings.
    database = {}
    in_vuln_db = False
    for line in version_text.splitlines():
        stripped = line.strip()
        if stripped.startswith("Vulnerability DB"):
            in_vuln_db = True
            continue
        if stripped.startswith("Java DB"):
            in_vuln_db = False
            continue
        if in_vuln_db and ":" in stripped:
            key, _, value = stripped.partition(":")
            database[key.strip()] = value.strip()
    if "UpdatedAt" not in database:
        problems.append("vulnerability database timestamp missing (scan database unavailable?)")
    summary["tool"] = version_text.splitlines()[0] if version_text else None
    summary["database"] = database
    summary["scope"] = "runtime image (OS packages + jar dependencies) and the runtime dependency tree"

    # A scan that evaluated nothing is not a clean scan.
    for name in ("trivy-dependencies.json", "trivy-image.json"):
        data = load(os.path.join(args.dir, name))
        results = data.get("Results") or []
        if not results:
            problems.append(f"{name}: the scan evaluated no targets")
        summary.setdefault("scanned_targets", {})[name] = [
            f"{result.get('Target')} ({result.get('Class')}/{result.get('Type')})"
            for result in results]

    for name in ("trivy-dependencies.json", "trivy-image.json"):
        findings = vuln_findings(os.path.join(args.dir, name))
        summary[name] = {"findings": findings}
        problems.extend(f"{name}: {finding}" for finding in findings)

    image_secrets = secret_findings(os.path.join(args.dir, "trivy-image-secrets.json"))
    summary["image_secrets"] = image_secrets
    problems.extend(f"image secret: {secret}" for secret in image_secrets)

    repo_secrets = secret_findings(os.path.join(args.dir, "trivy-repo-secrets.json"))
    # A local .env is ignored by git and never shipped; the scan runs over the tracked tree only,
    # so any hit here is a committed credential.
    summary["repository_secrets"] = repo_secrets
    problems.extend(f"committed secret: {secret}" for secret in repo_secrets)

    file_list = ""
    file_list_path = os.path.join(args.dir, "image-file-list.txt")
    if os.path.exists(file_list_path):
        file_list = open(file_list_path).read()
    leaked = [line for line in file_list.splitlines()
              if re.search(r"(^|/)(\.env|credentials[^/]*)$", line.strip())]
    summary["image_credential_files"] = leaked
    problems.extend(f"image contains credential file: {line}" for line in leaked)

    summary["problems"] = problems
    with open(args.out, "w") as handle:
        json.dump(summary, handle, indent=2)
    print(json.dumps(summary))
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
