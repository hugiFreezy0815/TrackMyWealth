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
# response field would not count as breaking at all. A new value of a response enum breaks a client
# that handles each value and fails on one it does not know, whatever level oasdiff gives it.
SEVERITY_OVERRIDES = {
    "response-optional-property-removed": "ERR",
    "response-property-enum-value-added": "ERR",
}

# The lowest level that fails the check: oasdiff's warnings are changes that break some clients, and
# a PR that means them declares them like an error. Only "info" changes pass undeclared (and shown).
FAIL_ON = "WARN"

# `oasdiff breaking --fail-on WARN` exits with 1 when it found such a change; anything else but 0
# means it could not compare the documents.
BREAKING_EXIT_CODE = 1

# The note's heading; the section runs up to the next heading of the same or a higher level outside
# a fenced code block, so a `# comment` line in a shell snippet belongs to the note.
NOTE_HEADING_LINE = re.compile(
    r"^##[ \t]+" + re.escape(NOTE_HEADING) + r"[ \t]*#*[ \t]*$", re.IGNORECASE
)
SECTION_END = re.compile(r"^#{1,2}[ \t]")
FENCE = re.compile(r"^[ ]{0,3}(?P<fence>`{3,}|~{3,})")
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
    lines: Optional[List[str]] = None
    fence: Optional[str] = None
    for line in (body or "").replace("\r\n", "\n").split("\n"):
        opening = FENCE.match(line)
        if fence is not None:
            # In a code block: a line of only the same fence character, at least as many, ends it.
            closing = line.strip()
            if len(closing) >= len(fence) and not closing.lstrip(fence[0]) and opening:
                fence = None
        elif opening:
            fence = opening.group("fence")
        elif lines is None:
            if NOTE_HEADING_LINE.match(line):
                lines = []
            continue
        elif SECTION_END.match(line):
            break
        if lines is not None:
            lines.append(line)
    if lines is None:
        return ""
    return HTML_COMMENT.sub("", "\n".join(lines)).strip()


def verdict(diff: Diff, labels: Sequence[str], body: Optional[str]) -> Verdict:
    """Whether the PR may change the contract the way oasdiff found."""
    if diff.exit_code == 0:
        # Changes below FAIL_ON (oasdiff's "info") do not fail the check but are still shown.
        report = f"\n{diff.report}" if diff.report else ""
        return Verdict(True, f"No breaking change to the API contract.{report}")
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
                FAIL_ON,
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
    try:
        labels = json.loads(raw)
    except ValueError:
        labels = None
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
