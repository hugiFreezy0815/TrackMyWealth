#!/usr/bin/env python3
"""
Create GitHub Issues for every TrackMyWealth user story, directly in a GitHub
repository's issue tracker, from the markdown files under docs/user-stories/.

WHY THIS SCRIPT EXISTS
-----------------------
The environment that generated this repository (an Anthropic Claude sandbox)
has no authenticated GitHub write access, so it cannot call the GitHub API on
your behalf. This script does the same job locally, using your own `gh` CLI
login, so the ~85 user stories in docs/user-stories/EPIC-*.md land as real,
individually-trackable GitHub Issues in your repository.

WHAT IT DOES
------------
1. Parses every docs/user-stories/EPIC-*.md file and splits it into
   individual user stories (each "## US-<epic>-<n> — <title>" section).
2. Also parses docs/user-stories/BACKLOG-remaining-epics.md, which covers
   EPIC 20-24 and 29-32 (real MVP-or-near-MVP scope that hasn't been
   decomposed into full section-40 stories yet) — each of those epics
   becomes ONE issue containing its starter-story checklist, since there is
   no per-story breakdown to create individual issues from yet.
3. Ensures a small set of labels exist in the target repo (epic-01 .. epic-32,
   priority-must / priority-should / priority-could, user-story, backlog).
4. Creates one GitHub Issue per story (or per backlog epic) via
   `gh issue create`, with the full section-40 template content (acceptance
   criteria, business rules, dependencies, priority, DoD, data-quality
   behaviour) as the issue body, tagged with its epic and priority labels.

REQUIREMENTS
------------
- GitHub CLI installed and authenticated: https://cli.github.com
      gh auth login
- Run from anywhere; pass the repo explicitly (see USAGE) or run this from
  inside a clone of the target repo, where `gh` can infer it.
- The target repository must exist. Issues do not need to be pre-enabled;
  `gh issue create` enables them on first use if the repo owner allows it.

USAGE
-----
    # Preview everything that WOULD be created, without creating anything:
    python3 scripts/create_github_issues.py --repo hugiFreezy0815/TrackMyWealth --dry-run

    # Actually create the issues (idempotency note: this does NOT check for
    # already-existing issues with the same title — run --dry-run first, and
    # do not run this twice against the same repo without clearing it):
    python3 scripts/create_github_issues.py --repo hugiFreezy0815/TrackMyWealth

    # Resume after a partial run / rate limit (skip the first N issues):
    python3 scripts/create_github_issues.py --repo hugiFreezy0815/TrackMyWealth --start-at 42

    # Only one epic, e.g. while testing:
    python3 scripts/create_github_issues.py --repo hugiFreezy0815/TrackMyWealth --only EPIC-01

This script deliberately does not use the GitHub REST API directly (no token
handling of its own) — it shells out to `gh`, so your existing `gh auth
login` session is the only credential involved, and nothing is stored or
transmitted anywhere else.
"""

import argparse
import re
import subprocess
import sys
import time
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
STORIES_DIR = REPO_ROOT / "docs" / "user-stories"

STORY_HEADER_RE = re.compile(r"^##\s+(US-(\d+)-(\d+))\s+—\s+(.+)$", re.MULTILINE)
EPIC_TITLE_RE = re.compile(r"^#\s+EPIC\s+(\d+)\s+—\s+(.+)$", re.MULTILINE)
PRIORITY_RE = re.compile(r"\*\*Priority:\*\*\s*([A-Za-z]+)")

BACKLOG_EPIC_HEADER_RE = re.compile(r"^##\s+EPIC\s+(\d+)\s+—\s+(.+)$", re.MULTILINE)


def sh(*args, input_text=None):
    result = subprocess.run(args, input=input_text, text=True, capture_output=True)
    return result.returncode, result.stdout, result.stderr


def ensure_gh_available():
    rc, out, err = sh("gh", "--version")
    if rc != 0:
        print("ERROR: GitHub CLI ('gh') was not found on PATH. Install it from "
              "https://cli.github.com and run 'gh auth login' first.", file=sys.stderr)
        sys.exit(1)
    rc, out, err = sh("gh", "auth", "status")
    if rc != 0:
        print("ERROR: 'gh auth status' failed — you are not logged in. Run "
              "'gh auth login' first.\n" + err, file=sys.stderr)
        sys.exit(1)


def parse_epic_file(path: Path):
    """Return (epic_num, epic_title, [ {id, title, priority, body} ])."""
    text = path.read_text(encoding="utf-8")
    epic_match = EPIC_TITLE_RE.search(text)
    if not epic_match:
        return None
    epic_num = int(epic_match.group(1))
    epic_title = epic_match.group(2).strip()

    headers = list(STORY_HEADER_RE.finditer(text))
    stories = []
    for i, m in enumerate(headers):
        story_id = m.group(1)
        story_title = m.group(4).strip()
        start = m.end()
        end = headers[i + 1].start() if i + 1 < len(headers) else len(text)
        body = text[start:end]
        # Trim a trailing "---" section separator if present.
        body = re.sub(r"\n-{3,}\s*$", "", body).strip()
        priority_match = PRIORITY_RE.search(body)
        priority = priority_match.group(1).upper() if priority_match else None
        stories.append({
            "id": story_id,
            "title": story_title,
            "priority": priority,
            "body": body,
        })
    return epic_num, epic_title, stories


def parse_backlog_file(path: Path):
    """Return [ (epic_num, epic_title, body) ] for EPIC 20-24, 29-32."""
    text = path.read_text(encoding="utf-8")
    headers = list(BACKLOG_EPIC_HEADER_RE.finditer(text))
    entries = []
    for i, m in enumerate(headers):
        epic_num = int(m.group(1))
        epic_title = m.group(2).strip()
        start = m.end()
        end = headers[i + 1].start() if i + 1 < len(headers) else len(text)
        body = text[start:end].strip()
        entries.append((epic_num, epic_title, body))
    return entries


def priority_label(priority: str):
    if not priority:
        return None
    mapping = {"MUST": "priority-must", "SHOULD": "priority-should", "COULD": "priority-could"}
    return mapping.get(priority)


def collect_labels(story_items, backlog_items):
    labels = {"user-story", "backlog"}
    for epic_num, _title, stories in story_items:
        labels.add(f"epic-{epic_num:02d}")
        for s in stories:
            pl = priority_label(s["priority"])
            if pl:
                labels.add(pl)
    for epic_num, _title, _body in backlog_items:
        labels.add(f"epic-{epic_num:02d}")
    return sorted(labels)


LABEL_COLORS = {
    "user-story": "0e8a16",
    "backlog": "d4c5f9",
    "priority-must": "b60205",
    "priority-should": "fbca04",
    "priority-could": "c5def5",
}


def ensure_labels(repo: str, labels, dry_run: bool):
    for label in labels:
        color = LABEL_COLORS.get(label, "5319e7")  # default color for epic-NN labels
        if dry_run:
            print(f"[dry-run] would ensure label exists: {label} (#{color})")
            continue
        rc, out, err = sh("gh", "label", "create", label, "--repo", repo,
                           "--color", color, "--force")
        if rc != 0:
            print(f"WARNING: could not create/update label '{label}': {err.strip()}", file=sys.stderr)


def create_issue(repo: str, title: str, body: str, labels, dry_run: bool):
    if dry_run:
        print(f"[dry-run] would create issue: {title}")
        print(f"          labels: {', '.join(labels)}")
        return True
    args = ["gh", "issue", "create", "--repo", repo, "--title", title, "--body", body]
    for label in labels:
        args += ["--label", label]
    rc, out, err = sh(*args)
    if rc != 0:
        print(f"ERROR creating issue '{title}': {err.strip()}", file=sys.stderr)
        return False
    print(f"created: {title}  ->  {out.strip()}")
    return True


def build_story_body(epic_num: int, epic_title: str, story: dict) -> str:
    return (
        f"{story['body']}\n\n"
        f"---\n"
        f"_Part of EPIC {epic_num:02d} — {epic_title}. "
        f"Generated from `docs/user-stories/` in the TrackMyWealth repository; "
        f"see that directory for the full backlog and section-40 story template._"
    )


def build_backlog_body(epic_num: int, epic_title: str, body: str) -> str:
    return (
        f"{body}\n\n"
        f"---\n"
        f"_This epic has not yet been decomposed into individual section-40 user "
        f"stories — see `docs/user-stories/BACKLOG-remaining-epics.md`. Decompose "
        f"it into its own stories (and its own issues) during the sprint that "
        f"picks it up; the starter stories above are the scoped starting point._"
    )


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--repo", required=True, help="owner/repo, e.g. hugiFreezy0815/TrackMyWealth")
    parser.add_argument("--dry-run", action="store_true", help="Print what would be created; create nothing.")
    parser.add_argument("--start-at", type=int, default=0, help="Skip the first N issues (resume after a partial run).")
    parser.add_argument("--only", type=str, default=None, help="Only process one epic file, e.g. EPIC-01")
    parser.add_argument("--sleep", type=float, default=1.0, help="Seconds to sleep between issue creations (rate-limit friendly).")
    args = parser.parse_args()

    if not args.dry_run:
        ensure_gh_available()

    epic_files = sorted(STORIES_DIR.glob("EPIC-*.md"))
    if args.only:
        epic_files = [f for f in epic_files if f.name.startswith(args.only)]
        if not epic_files:
            print(f"No epic file matches --only {args.only}", file=sys.stderr)
            sys.exit(1)

    story_items = []
    for f in epic_files:
        parsed = parse_epic_file(f)
        if parsed:
            story_items.append(parsed)

    backlog_items = []
    backlog_file = STORIES_DIR / "BACKLOG-remaining-epics.md"
    if backlog_file.exists() and not args.only:
        backlog_items = parse_backlog_file(backlog_file)

    total_stories = sum(len(s) for _, _, s in story_items)
    total_backlog = len(backlog_items)
    print(f"Parsed {len(story_items)} epic file(s): {total_stories} user stories.")
    if backlog_items:
        print(f"Parsed {total_backlog} not-yet-decomposed backlog epics (one issue each).")

    labels = collect_labels(story_items, backlog_items)
    print(f"\nEnsuring {len(labels)} labels exist in {args.repo} ...")
    ensure_labels(args.repo, labels, args.dry_run)

    print(f"\nCreating {total_stories + total_backlog} issues in {args.repo} "
          f"(sleep={args.sleep}s between calls) ...\n")

    counter = 0
    created = 0
    failed = 0

    for epic_num, epic_title, stories in story_items:
        for story in stories:
            counter += 1
            if counter <= args.start_at:
                continue
            title = f"[{story['id']}] {story['title']}"
            body = build_story_body(epic_num, epic_title, story)
            story_labels = [f"epic-{epic_num:02d}", "user-story"]
            pl = priority_label(story["priority"])
            if pl:
                story_labels.append(pl)
            ok = create_issue(args.repo, title, body, story_labels, args.dry_run)
            if ok:
                created += 1
            else:
                failed += 1
            if not args.dry_run:
                time.sleep(args.sleep)

    for epic_num, epic_title, body in backlog_items:
        counter += 1
        if counter <= args.start_at:
            continue
        title = f"[EPIC {epic_num:02d}] {epic_title} (not yet decomposed into stories)"
        issue_body = build_backlog_body(epic_num, epic_title, body)
        epic_labels = [f"epic-{epic_num:02d}", "backlog"]
        ok = create_issue(args.repo, title, issue_body, epic_labels, args.dry_run)
        if ok:
            created += 1
        else:
            failed += 1
        if not args.dry_run:
            time.sleep(args.sleep)

    print(f"\nDone. {created} issue(s) created" + (f", {failed} failed" if failed else "") + ".")
    if failed:
        sys.exit(1)


if __name__ == "__main__":
    main()
