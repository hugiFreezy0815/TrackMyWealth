"""Tests for scripts/api_breaking.py (#372): when a pull request may change the API contract.

The decision is tested against a fake oasdiff. RealOasdiffTest runs the real one on the fixtures in
fixtures/api, one per acceptance criterion; it needs the oasdiff executable (OASDIFF, or oasdiff on
the PATH) and is skipped without it. The "API contract" workflow installs oasdiff and runs it before
the check itself.
"""

from __future__ import annotations

import io
import json
import os
import shutil
import sys
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path
from typing import List
from unittest import mock

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))

import api_breaking  # noqa: E402
from api_breaking import LABEL, Diff, check, labels_from, note_of, verdict  # noqa: E402

FIXTURES = Path(__file__).resolve().parent / "fixtures" / "api"
BASE = FIXTURES / "base.yaml"
REPORT = (
    "1 changes: 1 error, 0 warning, 0 info\n"
    "error\t[response-optional-property-removed] at revision.yaml\n"
    "\tin API GET /api/v1/accounts\n"
    "\t\tremoved the optional property `items/currency` from the response with the `200` status"
)
NOTE = "## API breaking change\n\n`currency` moves to `balance.currency`; the mobile app reads both.\n"
BREAKING = Diff(1, REPORT)


class FakeOasdiff:
    def __init__(self, diff: Diff):
        self.diff = diff
        self.calls: List[List[str]] = []
        self.levels = ""

    def __call__(self, args: List[str]) -> Diff:
        self.calls.append(args)
        self.levels = Path(args[args.index("--severity-levels") + 1]).read_text()
        return self.diff


def quietly(fn, *args, **kwargs):
    with redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()):
        return fn(*args, **kwargs)


class NoteTest(unittest.TestCase):
    def test_the_section_text_up_to_the_next_heading(self):
        body = "## Summary\nRenames a field.\n\n" + NOTE + "\n## Test plan\n- run it\n"
        self.assertEqual(note_of(body), NOTE.split("\n\n", 1)[1].strip())

    def test_a_deeper_heading_belongs_to_the_section(self):
        body = "## API breaking change\n### Why\nThe old name was wrong.\n## Test plan\n"
        self.assertEqual(note_of(body), "### Why\nThe old name was wrong.")

    def test_no_section_an_empty_one_or_only_a_template_comment_is_no_note(self):
        for body in (
            None,
            "",
            "Renames a field.",
            "## API breaking change\n\n## Test plan\n- run it\n",
            "## API breaking change\n<!-- What breaks for clients, and why -->\n",
            "API breaking change: yes",
            "### API breaking change\nnested too deep\n",
        ):
            with self.subTest(body=body):
                self.assertEqual(note_of(body), "")

    def test_heading_case_trailing_hashes_and_crlf_are_accepted(self):
        self.assertEqual(note_of("## api Breaking Change ##\r\nIt breaks.\r\n"), "It breaks.")


class VerdictTest(unittest.TestCase):
    def test_no_breaking_change_passes_with_or_without_the_label(self):
        for labels in ([], [LABEL]):
            with self.subTest(labels=labels):
                self.assertTrue(verdict(Diff(0, "No changes detected"), labels, None).passed)

    def test_a_breaking_change_fails_and_lists_the_change(self):
        result = verdict(BREAKING, [], None)
        self.assertFalse(result.passed)
        self.assertIn("removed the optional property `items/currency`", result.message)
        self.assertIn("in API GET /api/v1/accounts", result.message)
        self.assertIn(f"the '{LABEL}' label and a '## API breaking change' section", result.message)

    def test_the_label_alone_is_not_enough(self):
        result = verdict(BREAKING, [LABEL, "sprint-6"], "Renames a field.")
        self.assertFalse(result.passed)
        self.assertIn("give the PR a '## API breaking change' section", result.message)
        self.assertNotIn("label", result.message.split("give the PR", 1)[1])

    def test_the_note_alone_is_not_enough(self):
        result = verdict(BREAKING, ["sprint-6"], NOTE)
        self.assertFalse(result.passed)
        self.assertIn(f"give the PR the '{LABEL}' label.", result.message)

    def test_label_and_note_together_pass_and_still_list_the_change(self):
        result = verdict(BREAKING, [LABEL], NOTE)
        self.assertTrue(result.passed)
        self.assertIn("removed the optional property `items/currency`", result.message)

    def test_oasdiff_failing_to_compare_fails_even_with_label_and_note(self):
        result = verdict(Diff(102, "Error: failed to load revision spec"), [LABEL], NOTE)
        self.assertFalse(result.passed)
        self.assertIn("could not compare", result.message)
        self.assertIn("exit code 102", result.message)


class CheckTest(unittest.TestCase):
    def test_compares_base_with_revision_failing_on_errors_with_the_overrides(self):
        oasdiff = FakeOasdiff(Diff(0, ""))
        revision = FIXTURES / "additive.yaml"
        self.assertTrue(check(BASE, revision, [], None, oasdiff).passed)
        args = oasdiff.calls[0]
        self.assertEqual(args[:3], ["breaking", str(BASE), str(revision)])
        self.assertEqual(args[args.index("--fail-on") + 1], "ERR")
        self.assertEqual(oasdiff.levels, "response-optional-property-removed ERR\n")

    def test_a_base_without_a_contract_passes_without_running_oasdiff(self):
        oasdiff = FakeOasdiff(BREAKING)
        result = check(FIXTURES / "missing.yaml", BASE, [], None, oasdiff)
        self.assertTrue(result.passed)
        self.assertIn("no API contract yet", result.message)
        self.assertEqual(oasdiff.calls, [])

    def test_deleting_the_contract_is_a_breaking_change(self):
        oasdiff = FakeOasdiff(Diff(0, ""))
        result = check(BASE, FIXTURES / "missing.yaml", [], None, oasdiff)
        self.assertFalse(result.passed)
        self.assertIn("deletes the API contract", result.message)
        self.assertTrue(check(BASE, FIXTURES / "missing.yaml", [LABEL], NOTE, oasdiff).passed)
        self.assertEqual(oasdiff.calls, [])


class MainTest(unittest.TestCase):
    def run_main(self, diff: Diff, labels: List[str], body: str) -> int:
        env = {"PR_LABELS": json.dumps(labels), "PR_BODY": body}
        args = ["--base", str(BASE), "--revision", str(FIXTURES / "field-removed.yaml")]
        with mock.patch.dict(os.environ, env):
            return quietly(api_breaking.main, args, FakeOasdiff(diff))

    def test_exit_code_follows_the_verdict_reading_labels_and_body_from_the_environment(self):
        self.assertEqual(self.run_main(BREAKING, [], ""), 1)
        self.assertEqual(self.run_main(BREAKING, [LABEL], NOTE), 0)
        self.assertEqual(self.run_main(Diff(0, ""), [], ""), 0)

    def test_labels_must_be_a_json_array_of_names(self):
        self.assertEqual(labels_from(None), [])
        self.assertEqual(labels_from('["api-breaking","sprint-6"]'), ["api-breaking", "sprint-6"])
        for raw in ('"api-breaking"', '{"name": "api-breaking"}', "[1]"):
            with self.subTest(raw=raw), self.assertRaises(SystemExit):
                labels_from(raw)


OASDIFF = os.environ.get("OASDIFF") or shutil.which("oasdiff")


@unittest.skipUnless(OASDIFF, "needs the oasdiff executable (OASDIFF or oasdiff on the PATH)")
class RealOasdiffTest(unittest.TestCase):
    """The acceptance criteria of #372 against the real oasdiff."""

    def check(self, fixture: str, labels=(), body=None):
        return check(BASE, FIXTURES / fixture, list(labels), body, api_breaking.run_oasdiff(OASDIFF))

    def test_a_removed_response_field_fails_and_is_listed(self):
        result = self.check("field-removed.yaml")
        self.assertFalse(result.passed, result.message)
        self.assertIn("response-optional-property-removed", result.message)
        self.assertIn("currency", result.message)

    def test_an_optional_parameter_made_required_fails_and_is_listed(self):
        result = self.check("parameter-required.yaml")
        self.assertFalse(result.passed, result.message)
        self.assertIn("request-parameter-became-required", result.message)
        self.assertIn("status", result.message)

    def test_a_declared_break_passes(self):
        self.assertTrue(self.check("field-removed.yaml", [LABEL], NOTE).passed)

    def test_an_additive_change_passes(self):
        result = self.check("additive.yaml")
        self.assertTrue(result.passed, result.message)

    def test_an_unchanged_contract_passes(self):
        self.assertTrue(self.check("base.yaml").passed)

    def test_an_unreadable_contract_fails_whatever_the_pr_says(self):
        result = self.check("unreadable.yaml", [LABEL], NOTE)
        self.assertFalse(result.passed)
        self.assertIn("could not compare", result.message)


if __name__ == "__main__":
    unittest.main()
