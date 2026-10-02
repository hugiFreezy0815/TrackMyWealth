#!/usr/bin/env python3
"""Merge a pull request only when every required check passed on its current head (#187).

GitHub can enforce required checks only through branch protection or rulesets, and neither is
available on this private repository's current plan (the API answers 403). Until it is, this
script is the way to merge: it refuses unless every required check ran and passed on the PR's
current head commit, no other check failed, and the PR has no conflicts. It then squash-merges
with --match-head-commit, so a commit pushed after the check cannot be merged unverified. It
never uses --admin. See CONTRIBUTING.md.

The required checks are the job names in REQUIRED_CHECKS. Every workflow that defines one runs on
every pull request and skips its work when its part of the repository is untouched, so all of
them always report; a skipped job counts as passed. .github/rulesets/main.json requires the same
checks for when the plan allows a ruleset, and scripts/tests/test_merge_pr.py keeps the three in
step.

Usage: scripts/merge_pr.py <pr-number> [--dry-run] [--delete-branch]
Needs the GitHub CLI (gh), logged in with access to the repository.
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
from dataclasses import dataclass
from typing import Callable, Dict, List, Optional, Sequence

# Job name -> workflow it belongs to. A check run is named after its job.
REQUIRED_CHECKS: Dict[str, str] = {
    "test": "Backend CI",
    "sql-lint": "Backend CI",
    "build-web": "Mobile Web CI",
    "semgrep": "Semgrep",
    "gitleaks": "Secret scanning",
    "scripts": "Scripts CI",
}

PASSED = {"success", "skipped", "neutral"}

# What a check run looks like when billing or a spending limit stopped GitHub from starting the
# job at all (PRs #180, #182 and #185): it fails within seconds with no steps run.
NOT_STARTED_HINT = (
    "If its log says the job was not started (billing or spending limit), fix that in the"
    " account settings and re-run it - the code was never checked."
)


@dataclass(frozen=True)
class CheckRun:
    id: int
    name: str
    status: str
    conclusion: Optional[str]
    url: str


@dataclass(frozen=True)
class PullRequest:
    number: int
    state: str
    is_draft: bool
    base: str
    head_sha: str
    mergeable: str


def latest_by_name(check_runs: Sequence[CheckRun]) -> Dict[str, CheckRun]:
    """The newest run of each check: a re-run supersedes the run it repeats."""
    latest: Dict[str, CheckRun] = {}
    for run in check_runs:
        if run.name not in latest or run.id > latest[run.name].id:
            latest[run.name] = run
    return latest


def problems(
    pr: PullRequest,
    check_runs: Sequence[CheckRun],
    required: Sequence[str] = tuple(REQUIRED_CHECKS),
    base: str = "main",
) -> List[str]:
    """Every reason not to merge, empty when the PR may be merged."""
    found: List[str] = []
    if pr.state != "OPEN":
        found.append(f"PR #{pr.number} is {pr.state.lower()}, not open.")
    if pr.is_draft:
        found.append(f"PR #{pr.number} is a draft.")
    if pr.base != base:
        found.append(f"PR #{pr.number} targets {pr.base}, not {base}.")
    if pr.state != "OPEN":
        pass  # whether a closed or merged PR could be merged is moot
    elif pr.mergeable == "CONFLICTING":
        found.append(f"PR #{pr.number} has merge conflicts with {pr.base}.")
    elif pr.mergeable != "MERGEABLE":
        found.append(
            f"GitHub has not worked out yet whether PR #{pr.number} can be merged"
            f" ({pr.mergeable}); try again in a moment."
        )

    latest = latest_by_name(check_runs)
    for name in required:
        run = latest.get(name)
        if run is None:
            found.append(f"Required check '{name}' has not reported on {pr.head_sha[:7]}.")
        elif run.status != "completed":
            found.append(f"Required check '{name}' is still {run.status}: {run.url}")
        elif run.conclusion not in PASSED:
            found.append(f"Required check '{name}' {run.conclusion}: {run.url}. {NOT_STARTED_HINT}")
    # A failure outside the required set (e.g. "backend-changes") is still a failure.
    for name, run in sorted(latest.items()):
        if name in required:
            continue
        if run.status != "completed":
            found.append(f"Check '{name}' is still {run.status}: {run.url}")
        elif run.conclusion not in PASSED:
            found.append(f"Check '{name}' {run.conclusion}: {run.url}")
    return found


Runner = Callable[[List[str]], str]


def run_gh(args: List[str]) -> str:
    result = subprocess.run(["gh", *args], check=True, capture_output=True, text=True)
    return result.stdout


def fetch_pull_request(number: int, gh: Runner = run_gh) -> PullRequest:
    data = json.loads(
        gh(
            [
                "pr",
                "view",
                str(number),
                "--json",
                "number,state,isDraft,baseRefName,headRefOid,mergeable",
            ]
        )
    )
    return PullRequest(
        number=data["number"],
        state=data["state"],
        is_draft=data["isDraft"],
        base=data["baseRefName"],
        head_sha=data["headRefOid"],
        mergeable=data["mergeable"],
    )


def fetch_check_runs(sha: str, gh: Runner = run_gh) -> List[CheckRun]:
    path = f"repos/{{owner}}/{{repo}}/commits/{sha}/check-runs?per_page=100"
    data = json.loads(gh(["api", path]))
    if data["total_count"] > len(data["check_runs"]):
        # Never decide on a partial list: an unseen failed run must not be missed.
        raise SystemExit(f"{sha[:7]} has more than 100 check runs; check them by hand.")
    return [
        CheckRun(
            id=run["id"],
            name=run["name"],
            status=run["status"],
            conclusion=run["conclusion"],
            url=run["html_url"],
        )
        for run in data["check_runs"]
    ]


def main(argv: Optional[Sequence[str]] = None, gh: Runner = run_gh) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("pr", type=int, help="pull request number")
    parser.add_argument("--dry-run", action="store_true", help="check only, do not merge")
    parser.add_argument("--delete-branch", action="store_true", help="delete the branch after")
    args = parser.parse_args(argv)

    pr = fetch_pull_request(args.pr, gh)
    reasons = problems(pr, fetch_check_runs(pr.head_sha, gh))
    if reasons:
        print(f"Not merging PR #{pr.number}:", file=sys.stderr)
        for reason in reasons:
            print(f"  - {reason}", file=sys.stderr)
        return 1
    print(f"PR #{pr.number}: every required check passed on {pr.head_sha[:7]}.")
    if args.dry_run:
        return 0
    merge = ["pr", "merge", str(pr.number), "--squash", "--match-head-commit", pr.head_sha]
    if args.delete_branch:
        merge.append("--delete-branch")
    gh(merge)
    print(f"Merged PR #{pr.number}.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
