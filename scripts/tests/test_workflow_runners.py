"""Regression guard (#237): workflow jobs must use explicitly pinned runner images.

A floating label such as ``ubuntu-latest`` moves to a new image on GitHub's schedule, so CI would
change underneath every open PR without a commit. Moving to a new image is a PR of its own.

The workflows are read without a YAML library (Scripts CI uses the standard library only): block
style, a ``jobs:`` key at the top level, each job a key one level deeper and its properties deeper
still, at whatever indentation the file uses. Anything this reader cannot follow is reported, never
passed.
"""

from __future__ import annotations

import re
import unittest
from pathlib import Path

WORKFLOWS = Path(__file__).resolve().parents[2] / ".github" / "workflows"

ALLOWED_RUNNERS = {"ubuntu-24.04", "macos-26"}

# A key line: indentation, a plain or quoted key, then the value (possibly empty).
KEY = re.compile(
    r"""^(?P<indent>\ *)(?P<quote>["']?)(?P<key>[A-Za-z0-9_.-]+)(?P=quote):(?:\s+(?P<value>.*))?$"""
)
# A comment: a whole line, or "#" after whitespace (YAML needs the space; "a#b" is no comment).
COMMENT = re.compile(r"(^|\s+)#.*$")


def _lines(content: str) -> list[str]:
    """The content's lines without comments, trailing whitespace or blank lines."""
    lines = []
    for raw in content.replace("\r\n", "\n").split("\n"):
        line = COMMENT.sub("", raw).rstrip()
        if line:
            lines.append(line)
    return lines


def _key(line: str) -> tuple[int, str, str] | None:
    match = KEY.match(line)
    if match is None:
        return None
    return len(match["indent"]), match["key"], (match["value"] or "").strip()


def runner_problems(name: str, content: str) -> list[str]:
    """Find missing, floating, or dynamic runner selectors, with the workflow's and job's name."""
    lines = _lines(content)
    start = next((i for i, line in enumerate(lines) if _key(line) == (0, "jobs", "")), None)
    if start is None:
        return [f"{name}: no jobs section"]
    body = []
    for line in lines[start + 1 :]:
        if not line.startswith(" "):
            break  # the next top-level key ends the jobs section
        body.append(line)
    if not body:
        return [f"{name}: no jobs found"]
    job_indent = len(body[0]) - len(body[0].lstrip(" "))

    jobs: dict[str, list[tuple[str, str]]] = {}
    current = None
    property_indent = None
    for line in body:
        parsed = _key(line)
        indent = len(line) - len(line.lstrip(" "))
        if indent == job_indent:
            if parsed is None or parsed[2]:
                return [f"{name}: cannot read the job line {line.strip()!r}"]
            current = parsed[1]
            jobs[current] = []
            property_indent = None
        elif parsed is not None and current is not None:
            if property_indent is None:
                property_indent = indent
            if indent == property_indent:
                jobs[current].append((parsed[1], parsed[2]))

    problems = []
    for job, properties in jobs.items():
        keys = [key for key, _ in properties]
        if "uses" in keys and "runs-on" not in keys:
            continue  # calls a reusable workflow: its own jobs choose (and are checked for) runners
        runners = [value.strip("\"'") for key, value in properties if key == "runs-on"]
        if len(runners) != 1 or runners[0] not in ALLOWED_RUNNERS:
            got = runners[0] if runners else "<missing>"
            problems.append(f"{name}: job '{job}' runs-on {got}; pin to a fixed image")
    return problems


class WorkflowRunnerTest(unittest.TestCase):
    def test_all_workflow_jobs_have_fixed_runner_images(self):
        workflows = sorted(WORKFLOWS.glob("*.yml")) + sorted(WORKFLOWS.glob("*.yaml"))
        self.assertTrue(workflows, "no workflow files discovered")
        problems = [
            problem
            for workflow in workflows
            for problem in runner_problems(workflow.name, workflow.read_text(encoding="utf-8"))
        ]
        self.assertEqual(problems, [])

    def test_floating_runner_names_report_workflow_and_job(self):
        content = "name: Bad\n\njobs:\n  build:\n    runs-on: ubuntu-latest\n    steps: []\n"
        self.assertEqual(
            runner_problems("bad.yml", content),
            ["bad.yml: job 'build' runs-on ubuntu-latest; pin to a fixed image"],
        )

    def test_missing_runner_is_rejected(self):
        content = "jobs:\n  build:\n    steps: []\n"
        self.assertEqual(
            runner_problems("missing.yml", content),
            ["missing.yml: job 'build' runs-on <missing>; pin to a fixed image"],
        )

    def test_dynamic_and_list_runners_are_not_a_fixed_image(self):
        for runner in ("${{ matrix.os }}", "[ubuntu-latest]", "[self-hosted, linux]"):
            with self.subTest(runner=runner):
                content = f"jobs:\n  build:\n    runs-on: {runner}\n"
                self.assertEqual(len(runner_problems("dynamic.yml", content)), 1)

    def test_explicit_linux_and_macos_are_accepted(self):
        content = (
            "jobs:\n"
            "  linux:\n    runs-on: ubuntu-24.04\n"
            "  apple:\n    runs-on: 'macos-26'\n"
        )
        self.assertEqual(runner_problems("good.yml", content), [])

    def test_comments_crlf_and_other_indentation_are_read(self):
        content = (
            "on: push # comment\r\n"
            "jobs: # all of them\r\n"
            "    # a comment line\r\n"
            "    build:\r\n"
            "        runs-on: ubuntu-24.04 # pinned (#237)\r\n"
            "        steps:\r\n"
            "            - run: echo 'runs-on: ubuntu-latest'\r\n"
        )
        self.assertEqual(runner_problems("styled.yml", content), [])

    def test_quoted_job_keys_are_named_correctly(self):
        content = "jobs:\n  a:\n    runs-on: ubuntu-24.04\n  \"b\":\n    runs-on: ubuntu-latest\n"
        self.assertEqual(
            runner_problems("quoted.yml", content),
            ["quoted.yml: job 'b' runs-on ubuntu-latest; pin to a fixed image"],
        )

    def test_a_job_calling_a_reusable_workflow_needs_no_runner(self):
        content = "jobs:\n  call:\n    uses: ./.github/workflows/other.yml\n    secrets: inherit\n"
        self.assertEqual(runner_problems("caller.yml", content), [])

    def test_a_nested_runs_on_does_not_count_for_the_job(self):
        content = (
            "jobs:\n"
            "  build:\n"
            "    strategy:\n"
            "      matrix:\n"
            "        runs-on: ubuntu-24.04\n"
            "    steps: []\n"
        )
        self.assertIn("<missing>", runner_problems("nested.yml", content)[0])

    def test_unreadable_layouts_are_reported_not_passed(self):
        self.assertEqual(runner_problems("none.yml", "on: push\n"), ["none.yml: no jobs section"])
        self.assertEqual(
            runner_problems("flow.yml", "jobs: {build: {runs-on: ubuntu-latest}}\n"),
            ["flow.yml: no jobs section"],
        )
        self.assertEqual(
            runner_problems("empty.yml", "jobs:\non: push\n"), ["empty.yml: no jobs found"]
        )


if __name__ == "__main__":
    unittest.main()
