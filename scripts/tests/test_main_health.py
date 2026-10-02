"""Tests for scripts/main_health.py (#187): one alert issue per workflow while it is red on main."""

from __future__ import annotations

import io
import json
import sys
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from typing import List, Optional

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "scripts"))

import main_health  # noqa: E402
from main_health import action_for, title_for  # noqa: E402

ENV = {
    "WORKFLOW": "Backend CI",
    "RUN_URL": "https://example.test/runs/7",
    "HEAD_SHA": "0123456789abcdef0123456789abcdef01234567",
}


class FakeGh:
    def __init__(self, open_issues: Optional[List[dict]] = None):
        self.calls: List[List[str]] = []
        self.open_issues = open_issues or []

    def __call__(self, args: List[str]) -> str:
        self.calls.append(args)
        if args[:2] == ["issue", "list"]:
            return json.dumps(self.open_issues)
        return ""

    def writes(self) -> List[List[str]]:
        return [call for call in self.calls if call[:2] != ["issue", "list"]]


def run(conclusion: str, gh: FakeGh) -> None:
    with redirect_stdout(io.StringIO()):
        main_health.main(dict(ENV, CONCLUSION=conclusion), gh)


class ActionForTest(unittest.TestCase):
    def test_decisions(self):
        self.assertEqual(action_for("failure", None), "open")
        self.assertEqual(action_for("startup_failure", None), "open")
        self.assertEqual(action_for("timed_out", 5), "comment")
        self.assertEqual(action_for("success", 5), "close")
        self.assertEqual(action_for("success", None), "none")
        # A cancelled or skipped run says nothing about the code.
        self.assertEqual(action_for("cancelled", 5), "none")
        self.assertEqual(action_for("skipped", None), "none")


class MainTest(unittest.TestCase):
    def test_a_red_run_opens_a_labelled_issue_naming_the_run(self):
        gh = FakeGh()
        run("failure", gh)
        label, create = gh.writes()
        self.assertEqual(label[:3], ["label", "create", "ci-red"])
        self.assertIn("--force", label, "creating the label must not fail when it exists")
        self.assertEqual(create[:4], ["issue", "create", "--title", title_for("Backend CI")])
        body = create[create.index("--body") + 1]
        self.assertIn(ENV["RUN_URL"], body)
        self.assertIn("0123456", body)

    def test_a_red_run_comments_on_the_open_issue_instead_of_opening_another(self):
        gh = FakeGh([{"number": 9, "title": title_for("Backend CI")}])
        run("failure", gh)
        self.assertEqual([call[:3] for call in gh.writes()], [["issue", "comment", "9"]])

    def test_another_workflows_open_issue_is_left_alone(self):
        gh = FakeGh([{"number": 9, "title": title_for("Semgrep")}])
        run("success", gh)
        self.assertEqual(gh.writes(), [])

    def test_a_green_run_closes_the_open_issue(self):
        gh = FakeGh([{"number": 9, "title": title_for("Backend CI")}])
        run("success", gh)
        self.assertEqual([call[:3] for call in gh.writes()], [["issue", "close", "9"]])


if __name__ == "__main__":
    unittest.main()
