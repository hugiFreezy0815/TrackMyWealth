#!/usr/bin/env python3
"""Keep one GitHub issue open for each workflow that is red on main (#187).

Run by .github/workflows/main-health.yml after a watched workflow completes on main. A red run
opens the issue "[CI] main is red: <workflow>" (labelled ci-red), or comments on it if it is
already open; the next green run closes it. Without branch protection a red main can otherwise go
unnoticed until the next pull request trips over it.

Inputs come from the environment, never from the command line, so no workflow-provided text is
ever interpreted by a shell: WORKFLOW, CONCLUSION, RUN_URL and HEAD_SHA (plus GH_TOKEN for gh).
"""

from __future__ import annotations

import json
import os
import subprocess
import sys
from typing import Callable, Dict, List, Optional

LABEL = "ci-red"
RED = {"failure", "timed_out", "startup_failure", "action_required"}
GREEN = {"success"}

Runner = Callable[[List[str]], str]


def run_gh(args: List[str]) -> str:
    result = subprocess.run(["gh", *args], check=True, capture_output=True, text=True)
    return result.stdout


def title_for(workflow: str) -> str:
    return f"[CI] main is red: {workflow}"


def action_for(conclusion: str, open_issue: Optional[int]) -> str:
    """What to do: "open", "comment", "close" or "none".

    A cancelled or skipped run says nothing about the code, so it changes nothing.
    """
    if conclusion in RED:
        return "comment" if open_issue is not None else "open"
    if conclusion in GREEN and open_issue is not None:
        return "close"
    return "none"


def find_open_issue(workflow: str, gh: Runner) -> Optional[int]:
    issues = json.loads(
        gh(["issue", "list", "--state", "open", "--label", LABEL, "--json", "number,title"])
    )
    for issue in issues:
        if issue["title"] == title_for(workflow):
            return issue["number"]
    return None


def red_body(env: Dict[str, str]) -> str:
    return (
        f"`{env['WORKFLOW']}` concluded **{env['CONCLUSION']}** on main at"
        f" {env['HEAD_SHA'][:7]}: {env['RUN_URL']}\n\n"
        "main is the base of every open pull request, so fix it before merging anything else"
        " (CONTRIBUTING.md). If the run's log says the job was not started, it is a billing or"
        " spending-limit problem, not the code: fix it in the account settings and re-run.\n\n"
        "This issue closes itself when the workflow is green on main again."
    )


def main(env: Optional[Dict[str, str]] = None, gh: Runner = run_gh) -> int:
    env = dict(os.environ if env is None else env)
    workflow = env["WORKFLOW"]
    open_issue = find_open_issue(workflow, gh)
    action = action_for(env["CONCLUSION"], open_issue)
    if action == "open":
        gh(
            [
                "label",
                "create",
                LABEL,
                "--color",
                "B60205",
                "--description",
                "A workflow is red on main (scripts/main_health.py)",
                "--force",
            ]
        )
        gh(
            [
                "issue",
                "create",
                "--title",
                title_for(workflow),
                "--label",
                LABEL,
                "--body",
                red_body(env),
            ]
        )
    elif action == "comment":
        gh(["issue", "comment", str(open_issue), "--body", red_body(env)])
    elif action == "close":
        gh(
            [
                "issue",
                "close",
                str(open_issue),
                "--comment",
                f"`{workflow}` is green on main again at {env['HEAD_SHA'][:7]}: {env['RUN_URL']}",
            ]
        )
    print(f"{workflow}: {env['CONCLUSION']} -> {action}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
