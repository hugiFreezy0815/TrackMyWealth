"""Tests for scripts/merge_pr.py (#187): when a pull request may be merged, and that the script,
the ruleset and the workflows agree on the required checks."""

from __future__ import annotations

import io
import json
import re
import sys
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path
from typing import List

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))

import merge_pr  # noqa: E402
from merge_pr import REQUIRED_CHECKS, CheckRun, PullRequest, problems  # noqa: E402

HEAD = "a1b2c3d4e5f60718293a4b5c6d7e8f9012345678"


def pr(**changes) -> PullRequest:
    fields = dict(
        number=42,
        state="OPEN",
        is_draft=False,
        base="main",
        head_sha=HEAD,
        mergeable="MERGEABLE",
    )
    fields.update(changes)
    return PullRequest(**fields)


def run(name: str, conclusion="success", status="completed", run_id=1) -> CheckRun:
    return CheckRun(
        id=run_id,
        name=name,
        status=status,
        conclusion=conclusion if status == "completed" else None,
        url=f"https://example.test/runs/{run_id}",
    )


def all_green() -> List[CheckRun]:
    return [run(name, run_id=i) for i, name in enumerate(REQUIRED_CHECKS, start=1)]


class ProblemsTest(unittest.TestCase):
    def test_every_required_check_green_may_merge(self):
        self.assertEqual(problems(pr(), all_green()), [])

    def test_a_skipped_required_check_counts_as_passed(self):
        # The "changes" jobs skip "test"/"build-web" when their part of the repo is untouched.
        runs = [r if r.name != "test" else run("test", "skipped", run_id=r.id) for r in all_green()]
        self.assertEqual(problems(pr(), runs), [])

    def test_a_failed_required_check_blocks_and_explains_a_job_that_never_started(self):
        runs = [r if r.name != "test" else run("test", "failure", run_id=r.id) for r in all_green()]
        reasons = problems(pr(), runs)
        self.assertEqual(len(reasons), 1)
        self.assertIn("'test' failure", reasons[0])
        self.assertIn("not started", reasons[0])

    def test_a_missing_required_check_blocks(self):
        runs = [r for r in all_green() if r.name != "gitleaks"]
        self.assertEqual(
            problems(pr(), runs), [f"Required check 'gitleaks' has not reported on {HEAD[:7]}."]
        )

    def test_a_running_required_check_blocks(self):
        runs = [r if r.name != "semgrep" else run("semgrep", status="in_progress") for r in all_green()]
        self.assertIn("still in_progress", problems(pr(), runs)[0])

    def test_a_failed_check_outside_the_required_set_blocks_too(self):
        runs = all_green() + [run("backend-changes", "failure", run_id=99)]
        self.assertEqual(len(problems(pr(), runs)), 1)
        self.assertIn("'backend-changes' failure", problems(pr(), runs)[0])

    def test_a_rerun_supersedes_the_run_it_repeats(self):
        runs = all_green() + [run("test", "failure", run_id=0)]
        self.assertEqual(problems(pr(), runs), [], "the older failed run was re-run green")
        runs = all_green() + [run("test", "failure", run_id=100)]
        self.assertEqual(len(problems(pr(), runs)), 1, "the newest run failed")

    def test_cancelled_and_timed_out_are_not_passed(self):
        for conclusion in ("cancelled", "timed_out", "action_required", "stale"):
            runs = [r if r.name != "test" else run("test", conclusion) for r in all_green()]
            self.assertEqual(len(problems(pr(), runs)), 1, conclusion)

    def test_pr_state_conflicts_and_draft_block(self):
        self.assertIn("merge conflicts", problems(pr(mergeable="CONFLICTING"), all_green())[0])
        self.assertIn("has not worked out", problems(pr(mergeable="UNKNOWN"), all_green())[0])
        self.assertIn("draft", problems(pr(is_draft=True), all_green())[0])
        self.assertEqual(
            problems(pr(state="MERGED", mergeable="UNKNOWN"), all_green()),
            ["PR #42 is merged, not open."],
        )
        self.assertIn("targets develop", problems(pr(base="develop"), all_green())[0])


class FakeGh:
    """Answers gh calls from canned data and records them."""

    def __init__(self, check_runs: List[CheckRun], mergeable="MERGEABLE", total_count=None):
        self.calls: List[List[str]] = []
        self.check_runs = check_runs
        self.mergeable = mergeable
        self.total_count = total_count

    def __call__(self, args: List[str]) -> str:
        self.calls.append(args)
        if args[:2] == ["pr", "view"]:
            return json.dumps(
                {
                    "number": 42,
                    "state": "OPEN",
                    "isDraft": False,
                    "baseRefName": "main",
                    "headRefOid": HEAD,
                    "mergeable": self.mergeable,
                }
            )
        if args[0] == "api":
            runs = [
                {
                    "id": r.id,
                    "name": r.name,
                    "status": r.status,
                    "conclusion": r.conclusion,
                    "html_url": r.url,
                }
                for r in self.check_runs
            ]
            total = len(runs) if self.total_count is None else self.total_count
            return json.dumps({"total_count": total, "check_runs": runs})
        if args[:2] == ["pr", "merge"]:
            return ""
        raise AssertionError(f"unexpected gh call {args}")

    def merges(self) -> List[List[str]]:
        return [call for call in self.calls if call[:2] == ["pr", "merge"]]


def quietly(fn, *args, **kwargs):
    with redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()):
        return fn(*args, **kwargs)


class MainTest(unittest.TestCase):
    def test_green_pr_is_squash_merged_pinned_to_the_checked_head_and_never_as_admin(self):
        gh = FakeGh(all_green())
        self.assertEqual(quietly(merge_pr.main, ["42", "--delete-branch"], gh), 0)
        self.assertEqual(
            gh.merges(),
            [["pr", "merge", "42", "--squash", "--match-head-commit", HEAD, "--delete-branch"]],
        )
        self.assertNotIn("--admin", sum(gh.calls, []))

    def test_red_pr_is_not_merged(self):
        runs = [r if r.name != "test" else run("test", "failure") for r in all_green()]
        gh = FakeGh(runs)
        self.assertEqual(quietly(merge_pr.main, ["42"], gh), 1)
        self.assertEqual(gh.merges(), [])

    def test_dry_run_never_merges(self):
        gh = FakeGh(all_green())
        self.assertEqual(quietly(merge_pr.main, ["42", "--dry-run"], gh), 0)
        self.assertEqual(gh.merges(), [])

    def test_more_check_runs_than_one_page_stops_rather_than_deciding_on_part_of_them(self):
        gh = FakeGh(all_green(), total_count=150)
        with self.assertRaises(SystemExit):
            quietly(merge_pr.main, ["42"], gh)
        self.assertEqual(gh.merges(), [])


def jobs_of(workflow_file: Path) -> List[str]:
    text = workflow_file.read_text()
    jobs_section = text.split("\njobs:\n", 1)[1]
    return re.findall(r"^  ([a-z][a-z0-9-]*):\s*$", jobs_section, re.MULTILINE)


def pull_request_trigger(workflow_file: Path) -> str:
    """The workflow's pull_request trigger block, from its key to the next top-level `on` key."""
    on_section = workflow_file.read_text().split("\njobs:\n", 1)[0]
    match = re.search(r"^  pull_request:.*?(?=^  [a-z_]+:|\Z)", on_section, re.MULTILINE | re.DOTALL)
    return match.group(0) if match else ""


class ConsistencyTest(unittest.TestCase):
    """The script, the ruleset and the workflows must name the same checks, or the gate leaks."""

    workflows = {
        re.search(r"^name: (.+)$", f.read_text(), re.MULTILINE).group(1).strip(): f
        for f in (ROOT / ".github" / "workflows").glob("*.yml")
    }

    def test_the_ruleset_requires_exactly_the_scripts_checks_from_github_actions(self):
        ruleset = json.loads((ROOT / ".github" / "rulesets" / "main.json").read_text())
        checks = next(
            rule["parameters"]["required_status_checks"]
            for rule in ruleset["rules"]
            if rule["type"] == "required_status_checks"
        )
        self.assertEqual(sorted(c["context"] for c in checks), sorted(REQUIRED_CHECKS))
        self.assertTrue(all(c["integration_id"] == 15368 for c in checks), "GitHub Actions app")
        self.assertEqual(ruleset["bypass_actors"], [], "no one bypasses the gate")

    def test_every_required_check_is_a_job_of_its_workflow(self):
        for job, workflow in REQUIRED_CHECKS.items():
            self.assertIn(workflow, self.workflows, f"workflow '{workflow}' for '{job}'")
            self.assertIn(job, jobs_of(self.workflows[workflow]), f"job '{job}' in '{workflow}'")

    def test_job_names_are_unique_across_workflows(self):
        # The script keys check runs by name; two jobs sharing one could hide a failure.
        names = [job for f in self.workflows.values() for job in jobs_of(f)]
        self.assertEqual(len(names), len(set(names)), names)

    def test_every_workflow_with_a_required_check_runs_on_every_pull_request(self):
        for workflow in set(REQUIRED_CHECKS.values()):
            trigger = pull_request_trigger(self.workflows[workflow])
            self.assertTrue(trigger, f"'{workflow}' has no pull_request trigger")
            self.assertNotIn("paths", trigger, f"'{workflow}' must not filter pull requests by path")

    def test_main_health_watches_every_workflow_with_a_required_check(self):
        text = self.workflows["Main health"].read_text()
        watched = re.search(r"workflows: \[(.+)\]", text).group(1)
        self.assertEqual(
            sorted(w.strip() for w in watched.split(",")), sorted(set(REQUIRED_CHECKS.values()))
        )

    def test_every_job_runs_on_a_pinned_runner(self):
        for name, f in self.workflows.items():
            self.assertNotIn("ubuntu-latest", f.read_text(), name)


if __name__ == "__main__":
    unittest.main()
