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

The error codes (#374) are an open set in the contract (x-extensible-enum): adding one is additive,
and oasdiff cannot see one removed. So the check also compares the problem-code registry
(api/problem-codes.json, generated from the backend's ProblemCode and ImportRowCode): removing or
renaming a code, changing its statuses, client reaction or row kind, reusing a retired name or
dropping one from a retired list is a breaking change; adding or deprecating a code is not. An
unreadable registry fails like an unreadable contract.

Usage: scripts/api_breaking.py --base <base.yaml> --revision <revision.yaml> [--oasdiff <path>]
         [--base-codes <base.json> --revision-codes <revision.json>]
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
from typing import Callable, Dict, List, Optional, Sequence, Tuple

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

# A registry that cannot be read: like oasdiff failing, never excused by the label.
REGISTRY_UNREADABLE_EXIT_CODE = 2

# The registry's sections: the codes, their retired names, what a finding calls one, and the fields
# whose change breaks a client (a code's statuses and reaction, a row code's kind).
REGISTRY_SECTIONS = (
    ("problemCodes", "retiredProblemCodes", "problem code", ("statuses", "reaction")),
    ("importRowCodes", "retiredImportRowCodes", "import row code", ("kind",)),
)

# The note's heading; the section runs up to the next heading of the same or a higher level outside
# a fenced code block, so a `# comment` line in a shell snippet belongs to the note.
NOTE_HEADING_LINE = re.compile(
    r"^##[ \t]+" + re.escape(NOTE_HEADING) + r"[ \t]*#*[ \t]*$", re.IGNORECASE
)
SECTION_END = re.compile(r"^#{1,2}[ \t]")
# A code fence opens with three or more backticks or tildes. After backticks, the rest of the line
# holds no backtick (CommonMark), so ```inline code``` at the start of a line opens no code block.
FENCE = re.compile(r"^[ ]{0,3}(?P<fence>`{3,}(?=[^`]*$)|~{3,})")
# What oasdiff reports when the documents do not differ at all: nothing worth showing.
NO_CHANGES = re.compile(r"^\s*(no (breaking )?changes( detected| found)?\.?)?\s*$", re.IGNORECASE)
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
        report = "" if NO_CHANGES.match(diff.report) else f"\n{diff.report}"
        return Verdict(True, f"No breaking change to the API contract.{report}")
    if diff.exit_code != BREAKING_EXIT_CODE:
        return Verdict(
            False,
            f"The check could not compare the contracts (exit code {diff.exit_code}):"
            f"\n{diff.report}",
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
    base: Path,
    revision: Path,
    labels: Sequence[str],
    body: Optional[str],
    oasdiff: Oasdiff,
    base_codes: Optional[Path] = None,
    revision_codes: Optional[Path] = None,
) -> Verdict:
    if not base.is_file():
        return Verdict(
            True, "The base branch has no API contract yet; there is nothing to compare."
        )
    if not revision.is_file():
        contract = Diff(
            BREAKING_EXIT_CODE, f"The pull request deletes the API contract ({revision})."
        )
    else:
        contract = contract_diff(base, revision, oasdiff)
    codes = Diff(0, "") if base_codes is None else registry_diff(base_codes, revision_codes)
    return verdict(combined([contract, codes]), labels, body)


def contract_diff(base: Path, revision: Path, oasdiff: Oasdiff) -> Diff:
    with tempfile.TemporaryDirectory() as work:
        levels = Path(work) / "severity-levels.txt"
        levels.write_text(
            "".join(f"{rule} {level}\n" for rule, level in SEVERITY_OVERRIDES.items())
        )
        return oasdiff(
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


def combined(diffs: Sequence[Diff]) -> Diff:
    """One result from several comparisons: a failed comparison wins, then a breaking change."""
    failed = [diff for diff in diffs if diff.exit_code not in (0, BREAKING_EXIT_CODE)]
    if failed:
        return Diff(failed[0].exit_code, "\n\n".join(diff.report for diff in failed))
    report = "\n\n".join(diff.report for diff in diffs if diff.report)
    breaking = any(diff.exit_code == BREAKING_EXIT_CODE for diff in diffs)
    return Diff(BREAKING_EXIT_CODE if breaking else 0, report)


class RegistryError(ValueError):
    """The problem-code registry is not the file the backend generates."""


def load_registry(path: Path) -> Dict[str, object]:
    """The registry at {path}, checked to have the shape the comparison relies on."""
    try:
        registry = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as error:
        raise RegistryError(f"{path} is not readable JSON: {error}") from error
    if not isinstance(registry, dict):
        raise RegistryError(f"{path} is not a JSON object")
    for section, retired, _, _ in REGISTRY_SECTIONS:
        codes = registry.get(section)
        if not isinstance(codes, dict) or not all(
            isinstance(name, str) and isinstance(entry, dict) for name, entry in codes.items()
        ):
            raise RegistryError(f"{path}: '{section}' is not an object of codes")
        names = registry.get(retired)
        if not isinstance(names, list) or not all(isinstance(name, str) for name in names):
            raise RegistryError(f"{path}: '{retired}' is not a list of names")
    return registry


def registry_changes(
    base: Dict[str, object], revision: Dict[str, object]
) -> Tuple[List[str], List[str]]:
    """The breaking and the additive changes from {base}'s registry to {revision}'s."""
    breaking: List[str] = []
    additive: List[str] = []
    for section, retired_key, label, compared in REGISTRY_SECTIONS:
        old: Dict[str, Dict[str, object]] = base[section]  # type: ignore[assignment]
        new: Dict[str, Dict[str, object]] = revision[section]  # type: ignore[assignment]
        old_retired: List[str] = base[retired_key]  # type: ignore[assignment]
        new_retired: List[str] = revision[retired_key]  # type: ignore[assignment]
        for name, entry in old.items():
            if name not in new:
                breaking.append(f"removed the {label} `{name}`")
                continue
            for field in compared:
                if entry.get(field) != new[name].get(field):
                    before = json.dumps(entry.get(field))
                    after = json.dumps(new[name].get(field))
                    breaking.append(
                        f"changed the {field} of the {label} `{name}` from {before} to {after}"
                    )
            if new[name].get("deprecated") is True and entry.get("deprecated") is not True:
                additive.append(f"deprecated the {label} `{name}`")
        for name in new:
            if name in old:
                continue
            if name in old_retired or name in new_retired:
                breaking.append(f"reuses the retired {label} name `{name}`")
            else:
                additive.append(f"added the {label} `{name}`")
        for name in old_retired:
            if name not in new_retired:
                breaking.append(f"dropped `{name}` from the retired {label} names")
    return breaking, additive


def registry_diff(base: Optional[Path], revision: Optional[Path]) -> Diff:
    """What the PR does to the problem-code registry, in oasdiff's report style."""
    if base is None or not base.is_file():
        return Diff(0, "")
    if revision is None or not revision.is_file():
        return Diff(
            BREAKING_EXIT_CODE,
            f"The pull request deletes the problem-code registry ({revision}).",
        )
    try:
        breaking, additive = registry_changes(load_registry(base), load_registry(revision))
    except RegistryError as error:
        return Diff(REGISTRY_UNREADABLE_EXIT_CODE, f"The problem-code registry: {error}")
    lines = [f"error\t[problem-code-registry] {change}" for change in breaking]
    lines += [f"info\t[problem-code-registry] {change}" for change in additive]
    return Diff(BREAKING_EXIT_CODE if breaking else 0, "\n".join(lines))


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
    parser.add_argument(
        "--base-codes", type=Path, help="the problem-code registry on the base branch"
    )
    parser.add_argument("--revision-codes", type=Path, help="the problem-code registry of the PR")
    args = parser.parse_args(argv)
    if (args.base_codes is None) != (args.revision_codes is None):
        parser.error("--base-codes and --revision-codes go together")
    result = check(
        args.base,
        args.revision,
        labels_from(os.environ.get("PR_LABELS")),
        os.environ.get("PR_BODY"),
        oasdiff or run_oasdiff(args.oasdiff),
        args.base_codes,
        args.revision_codes,
    )
    print(result.message, file=sys.stdout if result.passed else sys.stderr)
    return 0 if result.passed else 1


if __name__ == "__main__":
    sys.exit(main())
