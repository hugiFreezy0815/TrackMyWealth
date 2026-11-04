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
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path
from typing import List
from unittest import mock

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))

import api_breaking  # noqa: E402
from api_breaking import (  # noqa: E402
    LABEL,
    Diff,
    check,
    combined,
    labels_from,
    load_registry,
    note_of,
    registry_diff,
    verdict,
)

FIXTURES = Path(__file__).resolve().parent / "fixtures" / "api"
BASE = FIXTURES / "base.yaml"
REPORT = (
    "1 changes: 1 error, 0 warning, 0 info\n"
    "error\t[response-required-property-removed] at revision.yaml\n"
    "\tin API GET /api/v1/accounts\n"
    "\t\tremoved the required property `items/currency` from the response with the `200` status"
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

    def test_a_comment_line_in_a_code_block_does_not_end_the_section(self):
        snippet = "Clients call the new path:\n```bash\n# old call\ncurl /api/v1/accounts\n```\nDone."
        body = f"## API breaking change\n{snippet}\n## Test plan\n- run it\n"
        self.assertEqual(note_of(body), snippet)

    def test_a_longer_or_tilde_fence_holds_shorter_fences_and_headings(self):
        snippet = "~~~~\n```\n## not a heading\n~~~\n~~~~\nAfter."
        self.assertEqual(note_of(f"## API breaking change\n{snippet}\n# Next\n"), snippet)

    def test_the_section_after_inline_code_at_the_start_of_a_line_is_found(self):
        body = "## Summary\n```/api/v1/foo``` is gone.\n" + NOTE + "## Test plan\n- run it\n"
        self.assertEqual(note_of(body), NOTE.split("\n\n", 1)[1].strip())

    def test_inline_code_in_the_note_does_not_swallow_the_next_section(self):
        inline = "```GET /x``` now needs `status`.\n"
        self.assertEqual(note_of(f"## API breaking change\n{inline}## Test plan\n"), inline.strip())

    def test_the_heading_inside_a_code_block_is_not_the_section(self):
        self.assertEqual(note_of("```\n## API breaking change\nquoted\n```\n"), "")


class VerdictTest(unittest.TestCase):
    def test_no_breaking_change_passes_with_or_without_the_label(self):
        for labels in ([], [LABEL]):
            with self.subTest(labels=labels):
                self.assertTrue(verdict(Diff(0, "No changes detected"), labels, None).passed)

    def test_no_change_at_all_adds_nothing_to_the_message(self):
        for report in ("", "\n", "No changes detected", "No breaking changes"):
            with self.subTest(report=report):
                diff = combined([Diff(0, report), Diff(0, "")])
                self.assertEqual(
                    verdict(diff, [], None).message, "No breaking change to the API contract."
                )

    def test_an_unchanged_contract_beside_a_new_code_shows_only_the_code(self):
        added = "info\t[problem-code-registry] added the problem code `X`"
        diff = combined([Diff(0, "No changes detected"), Diff(0, added)])
        self.assertEqual(
            verdict(diff, [], None).message, f"No breaking change to the API contract.\n{added}"
        )

    def test_changes_below_the_failing_level_pass_and_are_shown(self):
        info = "0 error, 0 warning, 1 info\ninfo\t[response-optional-property-added]"
        result = verdict(Diff(0, info), [], None)
        self.assertTrue(result.passed)
        self.assertIn("response-optional-property-added", result.message)

    def test_a_breaking_change_fails_and_lists_the_change(self):
        result = verdict(BREAKING, [], None)
        self.assertFalse(result.passed)
        self.assertIn("removed the required property `items/currency`", result.message)
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
        self.assertIn("removed the required property `items/currency`", result.message)

    def test_oasdiff_failing_to_compare_fails_even_with_label_and_note(self):
        result = verdict(Diff(102, "Error: failed to load revision spec"), [LABEL], NOTE)
        self.assertFalse(result.passed)
        self.assertIn("could not compare", result.message)
        self.assertIn("exit code 102", result.message)


class CheckTest(unittest.TestCase):
    def test_compares_base_with_revision_failing_on_warnings_with_the_overrides(self):
        oasdiff = FakeOasdiff(Diff(0, ""))
        revision = FIXTURES / "additive.yaml"
        self.assertTrue(check(BASE, revision, [], None, oasdiff).passed)
        args = oasdiff.calls[0]
        self.assertEqual(args[:3], ["breaking", str(BASE), str(revision)])
        self.assertEqual(args[args.index("--fail-on") + 1], "WARN")
        self.assertEqual(
            oasdiff.levels,
            "response-property-enum-value-added ERR\n",
        )

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


def registry(problem_codes=None, rows=None, retired=(), retired_rows=()) -> dict:
    """A problem-code registry in the layout the backend generates (api/problem-codes.json)."""
    if problem_codes is None:
        problem_codes = {
            "NOT_FOUND": {"statuses": [404], "reaction": "RELOAD", "deprecated": False},
            "VERSION_CONFLICT": {
                "statuses": [412, 409],
                "reaction": "RELOAD_AND_RETRY",
                "deprecated": False,
            },
        }
    if rows is None:
        rows = {"IMPORT_ROW_ZERO_AMOUNT": {"kind": "ROW_SKIPPED", "deprecated": False}}
    return {
        "problemCodes": problem_codes,
        "retiredProblemCodes": list(retired),
        "importRowCodes": rows,
        "retiredImportRowCodes": list(retired_rows),
    }


class RegistryTest(unittest.TestCase):
    """#374: the codes are an open set in the contract, so the registry guards their removal."""

    def setUp(self):
        self.work = tempfile.TemporaryDirectory()
        self.addCleanup(self.work.cleanup)

    def write(self, name: str, content) -> Path:
        path = Path(self.work.name) / name
        path.write_text(content if isinstance(content, str) else json.dumps(content))
        return path

    def diff(self, base: dict, revision: dict) -> Diff:
        return registry_diff(self.write("base.json", base), self.write("revision.json", revision))

    def changed(self, **revision) -> dict:
        changed = registry()
        changed.update(revision)
        return changed

    def test_an_unchanged_registry_passes_silently(self):
        self.assertEqual(self.diff(registry(), registry()), Diff(0, ""))

    def test_an_added_code_is_additive_and_listed(self):
        codes = registry()["problemCodes"]
        codes["ACCOUNT_ARCHIVED"] = {"statuses": [409], "reaction": "SHOW", "deprecated": False}
        rows = dict(registry()["importRowCodes"], IMPORT_ROW_NEW={"kind": "ROW_ERROR"})
        diff = self.diff(registry(), registry(codes, rows))
        self.assertEqual(diff.exit_code, 0)
        self.assertIn("added the problem code `ACCOUNT_ARCHIVED`", diff.report)
        self.assertIn("added the import row code `IMPORT_ROW_NEW`", diff.report)

    def test_a_deprecated_code_is_additive(self):
        codes = registry()["problemCodes"]
        codes["NOT_FOUND"] = dict(codes["NOT_FOUND"], deprecated=True)
        diff = self.diff(registry(), registry(codes))
        self.assertEqual(diff.exit_code, 0)
        self.assertIn("deprecated the problem code `NOT_FOUND`", diff.report)

    def test_a_removed_or_renamed_code_is_breaking(self):
        codes = registry()["problemCodes"]
        codes["MISSING"] = codes.pop("NOT_FOUND")
        diff = self.diff(registry(), registry(codes, rows={}, retired=["NOT_FOUND"]))
        self.assertEqual(diff.exit_code, 1)
        self.assertIn("removed the problem code `NOT_FOUND`", diff.report)
        self.assertIn("removed the import row code `IMPORT_ROW_ZERO_AMOUNT`", diff.report)
        self.assertIn("added the problem code `MISSING`", diff.report)

    def test_a_changed_status_reaction_or_row_kind_is_breaking(self):
        codes = registry()["problemCodes"]
        codes["VERSION_CONFLICT"] = dict(codes["VERSION_CONFLICT"], statuses=[412])
        codes["NOT_FOUND"] = dict(codes["NOT_FOUND"], reaction="SHOW")
        rows = {"IMPORT_ROW_ZERO_AMOUNT": {"kind": "ROW_ERROR", "deprecated": False}}
        diff = self.diff(registry(), registry(codes, rows))
        self.assertEqual(diff.exit_code, 1)
        self.assertIn(
            "changed the statuses of the problem code `VERSION_CONFLICT` from [412, 409] to [412]",
            diff.report,
        )
        self.assertIn(
            'changed the reaction of the problem code `NOT_FOUND` from "RELOAD"', diff.report
        )
        self.assertIn(
            "changed the kind of the import row code `IMPORT_ROW_ZERO_AMOUNT`", diff.report
        )

    def test_a_retired_name_is_never_reused_and_the_retired_list_only_grows(self):
        codes = registry()["problemCodes"]
        codes["OLD_CODE"] = {"statuses": [409], "reaction": "SHOW", "deprecated": False}
        reused = self.diff(registry(retired=["OLD_CODE"]), registry(codes, retired=["OLD_CODE"]))
        self.assertEqual(reused.exit_code, 1)
        self.assertIn("reuses the retired problem code name `OLD_CODE`", reused.report)
        # Reused in the same PR that retires it (the name moved to the list and back): still caught.
        same_pr = self.diff(registry(), registry(codes, retired=["OLD_CODE"]))
        self.assertIn("reuses the retired problem code name `OLD_CODE`", same_pr.report)
        dropped = self.diff(registry(retired_rows=["IMPORT_ROW_OLD"]), registry())
        self.assertEqual(dropped.exit_code, 1)
        self.assertIn(
            "dropped `IMPORT_ROW_OLD` from the retired import row code names", dropped.report
        )

    def test_a_base_without_a_registry_has_nothing_to_compare(self):
        missing = Path(self.work.name) / "missing.json"
        diff = registry_diff(missing, self.write("r.json", registry()))
        self.assertEqual(diff, Diff(0, ""))

    def test_deleting_the_registry_is_breaking(self):
        gone = Path(self.work.name) / "gone.json"
        diff = registry_diff(self.write("base.json", registry()), gone)
        self.assertEqual(diff.exit_code, 1)
        self.assertIn("deletes the problem-code registry", diff.report)

    def test_an_unreadable_registry_fails_whatever_the_pr_says(self):
        base = self.write("base.json", registry())
        malformed = (
            "{not json",
            "[]",
            {"problemCodes": []},
            dict(registry(), retiredProblemCodes="X"),
        )
        for content in malformed:
            with self.subTest(content=content):
                diff = registry_diff(base, self.write("bad.json", content))
                self.assertEqual(diff.exit_code, api_breaking.REGISTRY_UNREADABLE_EXIT_CODE)
                result = verdict(diff, [LABEL], NOTE)
                self.assertFalse(result.passed)
                self.assertIn("could not compare", result.message)

    def test_a_registry_break_needs_the_label_and_the_note_like_a_contract_break(self):
        base = self.write("base.json", registry())
        revision = self.write("revision.json", registry(rows={}))
        oasdiff = FakeOasdiff(Diff(0, "No breaking changes to report"))
        failed = check(BASE, BASE, [], None, oasdiff, base, revision)
        self.assertFalse(failed.passed)
        self.assertIn("removed the import row code `IMPORT_ROW_ZERO_AMOUNT`", failed.message)
        self.assertTrue(check(BASE, BASE, [LABEL], NOTE, oasdiff, base, revision).passed)

    def test_an_oasdiff_failure_wins_over_a_registry_break(self):
        base = self.write("base.json", registry())
        revision = self.write("revision.json", registry(rows={}))
        result = check(BASE, BASE, [LABEL], NOTE, FakeOasdiff(Diff(102, "Error")), base, revision)
        self.assertFalse(result.passed)
        self.assertIn("exit code 102", result.message)

    def test_without_registries_only_the_contract_is_compared(self):
        self.assertTrue(check(BASE, BASE, [], None, FakeOasdiff(Diff(0, ""))).passed)

    def test_the_committed_registry_has_the_shape_the_check_reads(self):
        # The backend generates api/problem-codes.json; the check must be able to read it.
        committed = load_registry(ROOT / "api" / "problem-codes.json")
        self.assertIn("NOT_FOUND", committed["problemCodes"])
        path = ROOT / "api" / "problem-codes.json"
        self.assertEqual(registry_diff(path, path), Diff(0, ""))


class MainTest(unittest.TestCase):
    def run_main(self, diff: Diff, labels: List[str], body: str, extra=()) -> int:
        env = {"PR_LABELS": json.dumps(labels), "PR_BODY": body}
        args = ["--base", str(BASE), "--revision", str(FIXTURES / "field-removed.yaml"), *extra]
        with mock.patch.dict(os.environ, env):
            return quietly(api_breaking.main, args, FakeOasdiff(diff))

    def test_exit_code_follows_the_verdict_reading_labels_and_body_from_the_environment(self):
        self.assertEqual(self.run_main(BREAKING, [], ""), 1)
        self.assertEqual(self.run_main(BREAKING, [LABEL], NOTE), 0)
        self.assertEqual(self.run_main(Diff(0, ""), [], ""), 0)

    def test_the_registries_are_compared_when_given_and_only_together(self):
        with tempfile.TemporaryDirectory() as work:
            base = Path(work) / "base.json"
            base.write_text(json.dumps(registry()))
            revision = Path(work) / "revision.json"
            revision.write_text(json.dumps(registry(rows={})))
            codes = ["--base-codes", str(base), "--revision-codes", str(revision)]
            self.assertEqual(self.run_main(Diff(0, ""), [], "", codes), 1)
            self.assertEqual(self.run_main(Diff(0, ""), [LABEL], NOTE, codes), 0)
            with self.assertRaises(SystemExit):
                self.run_main(Diff(0, ""), [], "", ["--base-codes", str(base)])

    def test_labels_must_be_a_json_array_of_names(self):
        self.assertEqual(labels_from(None), [])
        self.assertEqual(labels_from('["api-breaking","sprint-6"]'), ["api-breaking", "sprint-6"])
        for raw in ('"api-breaking"', '{"name": "api-breaking"}', "[1]", "[api-breaking"):
            with self.subTest(raw=raw), self.assertRaises(SystemExit):
                labels_from(raw)


OASDIFF = os.environ.get("OASDIFF") or shutil.which("oasdiff")


@unittest.skipUnless(OASDIFF, "needs the oasdiff executable (OASDIFF or oasdiff on the PATH)")
class RealOasdiffTest(unittest.TestCase):
    """The acceptance criteria of #372 against the real oasdiff."""

    def check(self, fixture: str, labels=(), body=None, base: Path = BASE):
        return check(base, FIXTURES / fixture, list(labels), body, api_breaking.run_oasdiff(OASDIFF))

    def test_a_removed_response_field_fails_and_is_listed(self):
        # Without a severity override (#451): the field is required, as every response field is.
        result = self.check("field-removed.yaml")
        self.assertFalse(result.passed, result.message)
        self.assertIn("response-required-property-removed", result.message)
        self.assertIn("currency", result.message)

    def test_a_response_field_made_nullable_fails_and_is_listed(self):
        result = self.check("became-nullable.yaml")
        self.assertFalse(result.passed, result.message)
        self.assertIn("currency", result.message)

    def test_an_optional_parameter_made_required_fails_and_is_listed(self):
        result = self.check("parameter-required.yaml")
        self.assertFalse(result.passed, result.message)
        self.assertIn("request-parameter-became-required", result.message)
        self.assertIn("status", result.message)

    def test_a_value_added_to_a_response_enum_fails_and_is_listed(self):
        result = self.check("enum-value-added.yaml", base=FIXTURES / "enum-base.yaml")
        self.assertFalse(result.passed, result.message)
        self.assertIn("response-property-enum-value-added", result.message)
        self.assertIn("LOAN", result.message)

    def test_a_declared_break_passes(self):
        self.assertTrue(self.check("field-removed.yaml", [LABEL], NOTE).passed)

    def test_an_additive_change_passes(self):
        result = self.check("additive.yaml")
        self.assertTrue(result.passed, result.message)

    def test_an_unchanged_contract_passes_and_says_nothing_more(self):
        result = self.check("base.yaml")
        self.assertTrue(result.passed)
        self.assertEqual(result.message, "No breaking change to the API contract.")

    def test_an_unreadable_contract_fails_whatever_the_pr_says(self):
        result = self.check("unreadable.yaml", [LABEL], NOTE)
        self.assertFalse(result.passed)
        self.assertIn("could not compare", result.message)


if __name__ == "__main__":
    unittest.main()
