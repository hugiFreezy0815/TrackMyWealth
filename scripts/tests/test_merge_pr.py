"""Tests for scripts/merge_pr.py (#187): when a pull request may be merged, and that the script and
the workflows agree on which checks a change requires."""

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
from merge_pr import REQUIRED_CHECKS, CheckRun, PullRequest, problems, required_checks  # noqa: E402

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
        # A job whose `if:` is false reports as skipped (e.g. sql-lint without a pull request).
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
        runs = all_green() + [run("lint-extra", "failure", run_id=99)]
        self.assertEqual(len(problems(pr(), runs)), 1)
        self.assertIn("'lint-extra' failure", problems(pr(), runs)[0])

    def test_a_rerun_supersedes_the_run_it_repeats(self):
        runs = all_green() + [run("test", "failure", run_id=0)]
        self.assertEqual(problems(pr(), runs), [], "the older failed run was re-run green")
        runs = all_green() + [run("test", "failure", run_id=100)]
        self.assertEqual(len(problems(pr(), runs)), 1, "the newest run failed")

    def test_cancelled_and_timed_out_are_not_passed(self):
        for conclusion in ("cancelled", "timed_out", "action_required", "stale"):
            runs = [r if r.name != "test" else run("test", conclusion) for r in all_green()]
            self.assertEqual(len(problems(pr(), runs)), 1, conclusion)

    def test_a_pr_behind_its_base_blocks_and_says_how_to_update_it(self):
        reasons = problems(pr(behind_by=3), all_green())
        self.assertEqual(len(reasons), 1)
        self.assertIn("3 commit(s) behind main", reasons[0])
        self.assertIn("gh pr update-branch 42", reasons[0])

    def test_only_the_checks_a_change_requires_must_report(self):
        docs_only = required_checks(["README.md", "docs/architecture/database-schema.md"])
        self.assertEqual(docs_only, ["semgrep", "gitleaks"])
        green = [run(name, run_id=i) for i, name in enumerate(docs_only, start=1)]
        self.assertEqual(problems(pr(), green, docs_only), [])

    def test_a_changed_part_of_the_repository_requires_its_checks(self):
        self.assertEqual(
            required_checks(["backend/pom.xml"]), ["semgrep", "gitleaks", "test", "sql-lint"]
        )
        self.assertEqual(
            required_checks([".github/workflows/mobile-web-ci.yml"]),
            ["semgrep", "gitleaks", "build-web"],
        )
        self.assertEqual(required_checks(["scripts/merge_pr.py"]), ["semgrep", "gitleaks", "scripts"])
        self.assertEqual(
            required_checks(["backendish.md"]), ["semgrep", "gitleaks"], "a prefix is a directory"
        )

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

    def __init__(
        self,
        check_runs: List[CheckRun],
        mergeable="MERGEABLE",
        total_count=None,
        files=("backend/pom.xml", "mobile/package.json", "scripts/merge_pr.py"),
        behind_by=0,
    ):
        self.calls: List[List[str]] = []
        self.check_runs = check_runs
        self.mergeable = mergeable
        self.total_count = total_count
        self.files = list(files)
        self.behind_by = behind_by

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
        if args[0] == "api" and "/compare/" in args[1]:
            return json.dumps({"behind_by": self.behind_by})
        if args[0] == "api" and "/files" in " ".join(args):
            return "".join(f"{name}\n" for name in self.files)
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

    def test_a_docs_only_pr_merges_without_the_path_filtered_checks(self):
        gh = FakeGh(
            [run("semgrep", run_id=1), run("gitleaks", run_id=2)], files=["README.md"]
        )
        self.assertEqual(quietly(merge_pr.main, ["42"], gh), 0)
        self.assertEqual(len(gh.merges()), 1)

    def test_a_backend_pr_without_its_backend_checks_is_not_merged(self):
        gh = FakeGh([run("semgrep", run_id=1), run("gitleaks", run_id=2)], files=["backend/pom.xml"])
        self.assertEqual(quietly(merge_pr.main, ["42"], gh), 1)
        self.assertEqual(gh.merges(), [])

    def test_a_pr_behind_main_is_not_merged(self):
        gh = FakeGh(all_green(), behind_by=1)
        self.assertEqual(quietly(merge_pr.main, ["42"], gh), 1)
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
    """The script and the workflows must agree on which checks a change requires, or the gate
    leaks: a check the script does not wait for, or one it waits for that never runs."""

    workflows = {
        re.search(r"^name: (.+)$", f.read_text(), re.MULTILINE).group(1).strip(): f
        for f in (ROOT / ".github" / "workflows").glob("*.yml")
    }

    def test_every_required_check_is_a_job_of_its_workflow(self):
        for job, (workflow, _) in REQUIRED_CHECKS.items():
            self.assertIn(workflow, self.workflows, f"workflow '{workflow}' for '{job}'")
            self.assertIn(job, jobs_of(self.workflows[workflow]), f"job '{job}' in '{workflow}'")

    def test_job_names_are_unique_across_workflows(self):
        # The script keys check runs by name; two jobs sharing one could hide a failure.
        names = [job for f in self.workflows.values() for job in jobs_of(f)]
        self.assertEqual(len(names), len(set(names)), names)

    def test_the_scripts_paths_are_the_workflows_pull_request_paths(self):
        for job, (workflow, paths) in REQUIRED_CHECKS.items():
            trigger = pull_request_trigger(self.workflows[workflow])
            self.assertTrue(trigger, f"'{workflow}' has no pull_request trigger")
            filters = re.findall(r'^\s+- "([^"]+)"', trigger, re.MULTILINE)
            expected = [] if paths is None else [p + "**" if p.endswith("/") else p for p in paths]
            self.assertEqual(sorted(filters), sorted(expected), f"'{job}' in '{workflow}'")


if __name__ == "__main__":
    unittest.main()
