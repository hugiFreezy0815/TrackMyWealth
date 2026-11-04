#!/usr/bin/env python3
"""The breaking-change check of the API contract (#372): a pull request may break api/openapi.yaml
only on purpose, never by accident.

It compares the contract on the base branch with the PR's using `oasdiff breaking` and fails when
oasdiff finds a breaking change, listing every change it found, unless the PR says the break is
intended: it carries the label `api-breaking` AND its description has a `## API breaking change`
section that says what breaks and why (ADR 0006 allows additive changes only inside /api/v1). An
additive change (a new endpoint, a new optional field or parameter) passes without either.

When the base branch has no contract yet, there is nothing to compare against and the check passes.
A PR that deletes the contract is a breaking change like any other. A failure of oasdiff itself
(an unreadable document, a crash) fails the check and is never excused by the label.

Usage: scripts/api_breaking.py --base <base.yaml> --revision <revision.yaml> [--oasdiff <path>]
The PR's labels come from PR_LABELS (a JSON array of names), its description from PR_BODY.
Standard library only, like the other scripts that CI runs.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import tempfile
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, List, Optional, Sequence

LABEL = "api-breaking"
NOTE_HEADING = "API breaking change"

# oasdiff rules raised to error level, in its severity-levels file format ("<rule id> <level>").
# response-optional-property-removed is "info" in oasdiff: a client must not rely on an optional
# property being there. Ours do: the backend serializes every field of a response record, a null one
# as null (no @JsonInclude), and none is marked required in the document, so without this a removed
# response field would not count as breaking at all.
SEVERITY_OVERRIDES = {
    "response-optional-property-removed": "ERR",
}

# `oasdiff breaking --fail-on ERR` exits with 1 when it found a breaking change; anything else but 0
# means it could not compare the documents.
BREAKING_EXIT_CODE = 1

# The note's heading, then everything up to the next heading of the same or a higher level.
NOTE_SECTION = re.compile(
    r"^##[ \t]+" + re.escape(NOTE_HEADING) + r"[ \t]*#*[ \t]*$(?P<text>.*?)(?=^#{1,2}[ \t]|\Z)",
    re.IGNORECASE | re.MULTILINE | re.DOTALL,
)
HTML_COMMENT = re.compile(r"<!--.*?-->", re.DOTALL)


@dataclass(frozen=True)
class Diff:
    """What oasdiff said: its exit code and its report."""

    exit_code: int
    report: str


@dataclass(frozen=True)
class Verdict:
    passed: bool
    message: str


# Runs oasdiff with these arguments and returns what it said.
Oasdiff = Callable[[List[str]], Diff]


def note_of(body: Optional[str]) -> str:
    """The text of the description's `## API breaking change` section, without the HTML comments a
    template leaves in it; empty when there is no such section or it says nothing."""
    match = NOTE_SECTION.search((body or "").replace("\r\n", "\n"))
    return HTML_COMMENT.sub("", match.group("text")).strip() if match else ""


def verdict(diff: Diff, labels: Sequence[str], body: Optional[str]) -> Verdict:
    """Whether the PR may change the contract the way oasdiff found."""
    if diff.exit_code == 0:
        return Verdict(True, "No breaking change to the API contract.")
    if diff.exit_code != BREAKING_EXIT_CODE:
        return Verdict(
            False,
            f"oasdiff could not compare the contracts (exit code {diff.exit_code}):\n{diff.report}",
        )
    labelled = LABEL in labels
    noted = bool(note_of(body))
    if labelled and noted:
        return Verdict(
            True,
            f"Breaking change to the API contract, declared by the '{LABEL}' label and the"
            f" '## {NOTE_HEADING}' note:\n{diff.report}",
        )
    missing = [
        part
        for part, present in (
            (f"the '{LABEL}' label", labelled),
            (f"a '## {NOTE_HEADING}' section in the description saying what breaks and why", noted),
        )
        if not present
    ]
    return Verdict(
        False,
        f"Breaking change to the API contract:\n{diff.report}\n"
        "ADR 0006 allows additive changes only inside /api/v1. Make the change additive (a new"
        " field, parameter or endpoint beside the old one), or, if the break is intended, give the"
        f" PR {' and '.join(missing)}.",
    )


def check(
    base: Path, revision: Path, labels: Sequence[str], body: Optional[str], oasdiff: Oasdiff
) -> Verdict:
    if not base.is_file():
        return Verdict(True, "The base branch has no API contract yet; there is nothing to compare.")
    if not revision.is_file():
        diff = Diff(BREAKING_EXIT_CODE, f"The pull request deletes the API contract ({revision}).")
        return verdict(diff, labels, body)
    with tempfile.TemporaryDirectory() as work:
        levels = Path(work) / "severity-levels.txt"
        levels.write_text("".join(f"{rule} {level}\n" for rule, level in SEVERITY_OVERRIDES.items()))
        diff = oasdiff(
            [
                "breaking",
                str(base),
                str(revision),
                "--fail-on",
                "ERR",
                "--severity-levels",
                str(levels),
                "--format",
                "text",
            ]
        )
    return verdict(diff, labels, body)


def run_oasdiff(executable: str) -> Oasdiff:
    def run(args: List[str]) -> Diff:
        result = subprocess.run([executable, *args], capture_output=True, text=True, check=False)
        return Diff(result.returncode, (result.stdout + result.stderr).strip())

    return run


def labels_from(raw: Optional[str]) -> List[str]:
    """The PR's label names from PR_LABELS; a value that is not a JSON array of names stops the
    check rather than reading as "no label"."""
    if not raw:
        return []
    labels = json.loads(raw)
    if not isinstance(labels, list) or not all(isinstance(name, str) for name in labels):
        raise SystemExit(f"PR_LABELS is not a JSON array of label names: {raw}")
    return labels


def main(argv: Optional[Sequence[str]] = None, oasdiff: Optional[Oasdiff] = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n", 1)[0])
    parser.add_argument("--base", required=True, type=Path, help="the contract on the base branch")
    parser.add_argument("--revision", required=True, type=Path, help="the contract of the PR")
    parser.add_argument("--oasdiff", default="oasdiff", help="the oasdiff executable")
    args = parser.parse_args(argv)
    result = check(
        args.base,
        args.revision,
        labels_from(os.environ.get("PR_LABELS")),
        os.environ.get("PR_BODY"),
        oasdiff or run_oasdiff(args.oasdiff),
    )
    print(result.message, file=sys.stdout if result.passed else sys.stderr)
    return 0 if result.passed else 1


if __name__ == "__main__":
    sys.exit(main())
