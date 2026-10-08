#!/usr/bin/env python3
"""Fail closed unless the exact main-branch candidate has the latest complete green CI run."""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys

REQUIRED_JOBS = (
    "Unit and topology",
    "Real Kafka and PostgreSQL integration",
    "Migration, retry and dead letter",
    "Image and SCA",
    "Dashboard at four viewports",
)
ALLOWED_EVENTS = {"push", "workflow_dispatch"}


def require_candidate(version: str, candidate: str, workflow_sha: str, content_identity: str) -> None:
    if not re.fullmatch(r"v[0-9]+\.[0-9]+\.[0-9]+", version):
        raise ValueError("version must be a stable vMAJOR.MINOR.PATCH release")
    if not re.fullmatch(r"[0-9a-f]{40}", candidate):
        raise ValueError("candidate_sha must be a full lowercase commit SHA")
    if candidate != workflow_sha:
        raise ValueError("candidate_sha must equal the checked out main-branch workflow SHA")
    if not re.fullmatch(r"[0-9a-f]{64}", content_identity):
        raise ValueError("content_identity must be a 64-character lowercase SHA-256")


def select_latest_run(runs: list[dict], candidate: str) -> dict:
    matching = [run for run in runs if run.get("head_sha") == candidate
                and run.get("event") in ALLOWED_EVENTS]
    if not matching:
        raise ValueError("no main push or manual CI run exists for the exact candidate SHA")
    matching.sort(key=lambda run: (str(run.get("created_at", "")), int(run.get("id", 0)),
                                   int(run.get("run_attempt", 0))), reverse=True)
    latest = matching[0]
    if not latest.get("id") or latest.get("status") != "completed" or latest.get("conclusion") != "success":
        raise ValueError("the latest CI run for this candidate is not completed successfully")
    return latest


def verify_required_jobs(jobs: list[dict], run_attempt: int) -> dict:
    by_name = {job.get("name"): job for job in jobs}
    missing = [name for name in REQUIRED_JOBS if name not in by_name]
    if missing:
        raise ValueError(f"required CI jobs are missing: {missing}")
    failures = []
    selected = {}
    for name in REQUIRED_JOBS:
        job = by_name[name]
        if int(job.get("run_attempt", run_attempt)) != run_attempt:
            failures.append(f"{name}: result belongs to a different run attempt")
        if job.get("status") != "completed" or job.get("conclusion") != "success":
            failures.append(f"{name}: status={job.get('status')} conclusion={job.get('conclusion')}")
        selected[name] = {
            "id": job.get("id"),
            "status": job.get("status"),
            "conclusion": job.get("conclusion"),
            "run_attempt": int(job.get("run_attempt", run_attempt)),
        }
    if failures:
        raise ValueError("required CI jobs did not pass: " + "; ".join(failures))
    return selected


def gh_json(*args: str) -> dict:
    result = subprocess.run(["gh", "api", *args], capture_output=True, text=True,
                            check=False, timeout=30)
    if result.returncode:
        raise ValueError("GitHub Actions API request failed")
    try:
        value = json.loads(result.stdout)
    except json.JSONDecodeError as error:
        raise ValueError("GitHub Actions API returned invalid JSON") from error
    if not isinstance(value, dict):
        raise ValueError("GitHub Actions API returned an invalid response")
    return value


def check_remote(repo: str, candidate: str, version: str, content_identity: str,
                 workflow_sha: str) -> dict:
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repo):
        raise ValueError("repo must use owner/repository form")
    require_candidate(version, candidate, workflow_sha, content_identity)
    workflow_list = gh_json(f"repos/{repo}/actions/workflows")
    workflows = workflow_list.get("workflows")
    if not isinstance(workflows, list):
        raise ValueError("GitHub Actions API omitted workflows")
    ci_workflows = [workflow for workflow in workflows
                    if workflow.get("name") == "CI" and workflow.get("path") == ".github/workflows/ci.yml"]
    if len(ci_workflows) != 1 or not ci_workflows[0].get("id"):
        raise ValueError("could not uniquely identify the CI workflow")
    endpoint = f"repos/{repo}/actions/workflows/{ci_workflows[0]['id']}/runs"
    runs = []
    for event in sorted(ALLOWED_EVENTS):
        response = gh_json(endpoint, "-X", "GET", "-f", f"head_sha={candidate}",
                           "-f", f"event={event}", "-F", "per_page=100")
        if int(response.get("total_count", 0)) >= 100:
            raise ValueError("too many candidate CI runs to determine the latest result safely")
        workflow_runs = response.get("workflow_runs")
        if not isinstance(workflow_runs, list):
            raise ValueError("GitHub Actions API omitted workflow runs")
        runs.extend(workflow_runs)
    latest = select_latest_run(runs, candidate)
    attempt = int(latest.get("run_attempt", 0))
    if attempt < 1:
        raise ValueError("the latest CI run has no valid run attempt")
    jobs_response = gh_json(f"repos/{repo}/actions/runs/{latest['id']}/jobs", "-X", "GET", "-F", "per_page=100")
    if int(jobs_response.get("total_count", 0)) >= 100:
        raise ValueError("too many jobs to determine the complete CI result safely")
    jobs = jobs_response.get("jobs")
    if not isinstance(jobs, list):
        raise ValueError("GitHub Actions API omitted job results")
    selected_jobs = verify_required_jobs(jobs, attempt)
    return {
        "status": "PASSED",
        "version": version,
        "candidate_sha": candidate,
        "content_identity": content_identity,
        "ci_run": {
            "id": latest["id"], "event": latest["event"], "created_at": latest.get("created_at"),
            "run_attempt": attempt, "url": latest.get("html_url"),
        },
        "jobs": selected_jobs,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo", default=os.getenv("GITHUB_REPOSITORY", ""))
    parser.add_argument("--version", default=os.getenv("RELEASE_VERSION", ""))
    parser.add_argument("--candidate-sha", default=os.getenv("CANDIDATE_SHA", ""))
    parser.add_argument("--content-identity", default=os.getenv("CONTENT_IDENTITY", ""))
    parser.add_argument("--workflow-sha", default=os.getenv("GITHUB_SHA", ""))
    args = parser.parse_args()
    try:
        result = check_remote(args.repo, args.candidate_sha, args.version,
                              args.content_identity, args.workflow_sha)
    except (ValueError, OSError, subprocess.SubprocessError) as error:
        print(json.dumps({"status": "FAILED", "problems": [str(error)]}, indent=2))
        return 1
    print(json.dumps(result, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
