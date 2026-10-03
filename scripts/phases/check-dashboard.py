#!/usr/bin/env python3
"""Asserts the dashboard acceptance report (gate G12): four viewports in dark and light, every
page loading without console errors, no page-level horizontal overflow, a visible focus indicator
and a connected live-update socket."""

from __future__ import annotations

import argparse
import json
import sys

VIEWPORTS = {"320", "768", "1024", "1440"}
THEMES = {"dark", "light"}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--report", required=True)
    parser.add_argument("--out", required=True)
    args = parser.parse_args()

    report = json.load(open(args.report))
    pages = report.get("pages", [])
    problems = []

    seen = {(page.get("viewport"), page.get("theme")) for page in pages}
    for viewport in VIEWPORTS:
        for theme in THEMES:
            if (viewport, theme) not in seen:
                problems.append(f"missing capture for {viewport}-{theme}")

    for page in pages:
        label = f"{page.get('viewport')}-{page.get('theme')}"
        if page.get("status") != 200:
            problems.append(f"{label}: page status {page.get('status')}")
        if page.get("consoleErrors"):
            problems.append(f"{label}: {len(page['consoleErrors'])} console error(s): "
                            f"{page['consoleErrors'][0][:80]}")
        if page.get("horizontalOverflow"):
            problems.append(f"{label}: page-level horizontal overflow {page.get('overflow')}")
        keyboard = page.get("keyboard") or {}
        outline = str(keyboard.get("outlineWidth", ""))
        if not keyboard.get("focusable") or outline in ("", "0px"):
            problems.append(f"{label}: no visible focus indicator (outline={outline})")
        accessibility = page.get("accessibility") or {}
        if not accessibility.get("ran"):
            problems.append(f"{label}: accessibility scan did not run")
        else:
            for violation in accessibility.get("violations") or []:
                # Serious and critical findings fail the gate; minor ones are reported, not hidden.
                if violation.get("impact") in ("critical", "serious"):
                    problems.append(
                        f"{label}: accessibility {violation['id']} "
                        f"({violation['impact']}) on {violation['nodes']} node(s)")

        ws = page.get("wsStatus") or {}
        if isinstance(ws, dict):
            state, label = ws.get("state"), ws.get("label")
        else:  # older reports stored the bare label
            state, label = None, ws
        if state != "connected":
            problems.append(f"{label}: live socket not connected (state={state}, label={label!r})")

    summary = {
        "captures": len(pages),
        "accessibility": {
            page.get("viewport") + "-" + page.get("theme"): {
                "ran": (page.get("accessibility") or {}).get("ran"),
                "violations": (page.get("accessibility") or {}).get("violations") or [],
                "passes": (page.get("accessibility") or {}).get("passes"),
            }
            for page in pages
        },
        "viewports": sorted({page.get("viewport") for page in pages}),
        "themes": sorted({page.get("theme") for page in pages}),
        "problems": problems,
    }
    with open(args.out, "w") as handle:
        json.dump(summary, handle, indent=2)
    print(json.dumps(summary))
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
