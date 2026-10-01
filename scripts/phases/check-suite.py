#!/usr/bin/env python3
"""Aggregates surefire reports for a phase gate.

Keeps only the newest report per class (a retry can leave several), sums the totals, verifies
that every required class actually ran without skips, and writes a JSON summary.

Exit codes: 0 all good, 1 contract violated (missing class, failures, errors or skips).
"""

from __future__ import annotations

import argparse
import glob
import json
import os
import sys
import xml.etree.ElementTree as ET


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--reports", required=True)
    parser.add_argument("--out", required=True)
    parser.add_argument("--require", action="append", default=[])
    args = parser.parse_args()

    seen: set[str] = set()
    totals = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0}
    required: dict[str, dict] = {name: None for name in args.require}
    classes = []

    pattern = os.path.join(args.reports, "TEST-*.xml")
    for path in sorted(glob.glob(pattern), key=os.path.getmtime):
        root = ET.parse(path).getroot()
        name = root.get("name")
        if name in seen:
            continue
        seen.add(name)
        counts = {k: int(root.get(k)) for k in ("tests", "failures", "errors", "skipped")}
        for key, value in counts.items():
            totals[key] += value
        classes.append({"class": name, **counts})
        if name in required:
            required[name] = counts

    missing = [name for name, counts in required.items()
               if counts is None or counts["tests"] - counts["skipped"] <= 0]
    problems = []
    if totals["tests"] == 0:
        problems.append("no tests were reported")
    if totals["failures"] or totals["errors"] or totals["skipped"]:
        problems.append(f"totals contain failures/errors/skips: {totals}")
    if missing:
        problems.append(f"required classes did not run: {missing}")

    summary = {
        "totals": totals,
        "required_classes": required,
        "classes": classes,
        "problems": problems,
    }
    with open(args.out, "w") as handle:
        json.dump(summary, handle, indent=2)
    print(json.dumps({"totals": totals,
                      "required": {name: (counts or {}).get("tests") for name, counts in required.items()},
                      "problems": problems}))
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())