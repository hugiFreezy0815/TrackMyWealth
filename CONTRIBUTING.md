# Contributing

How a change gets into `main`. The coding rules themselves are in
[`docs/architecture/development-standards.md`](docs/architecture/development-standards.md); the
backlog is GitHub Issues (see the README's "Where to start as a developer").

## The rule

**Nothing is merged with a red, pending or missing required check.** Every pull request runs the
same required checks, and a pull request is merged only when all of them passed on its current head
commit. A bypass is never the normal workflow: no admin merge, no merging "because the failure is
unrelated". If a check is wrong, fix the check in its own pull request.

## Required checks

Each workflow below runs on **every** pull request, so its check always reports. Where only part of
the repository is affected, a small "changes" job skips the work when that part is untouched. A
skipped check counts as passed.

| Check | Workflow | What it verifies |
|---|---|---|
| `test` | Backend CI | `./mvnw -B verify`: the whole backend test suite against PostgreSQL, Spotless, PMD, SpotBugs, ArchUnit, the JaCoCo coverage gate, and the Docker image build. Skipped when `backend/` is untouched. |
| `sql-lint` | Backend CI | sqlfluff on new or changed Flyway migrations. Skipped when no migration changed. |
| `build-web` | Mobile Web CI | Type-check, lint, format check, Jest and the web export of `mobile/`. Skipped when `mobile/` is untouched. |
| `semgrep` | Semgrep | SAST over the whole repository. |
| `gitleaks` | Secret scanning | No secrets in the pull request's commits. |
| `scripts` | Scripts CI | Unit tests for the merge gate and the main-health alert, and the checks that keep this list, the script and the ruleset in step. |

The list lives in `REQUIRED_CHECKS` in [`scripts/merge_pr.py`](scripts/merge_pr.py) and in
[`.github/rulesets/main.json`](.github/rulesets/main.json); `scripts/tests/test_merge_pr.py` fails
the `scripts` check if the two, or the workflows' job names and triggers, drift apart. Every
workflow runs on a pinned runner image (`ubuntu-24.04`, not `ubuntu-latest`), so CI does not change
under a pull request when GitHub moves the `latest` label; move to a new image in its own pull
request.

## How to merge

GitHub cannot enforce the required checks on this repository yet: branch protection and rulesets
are not available on its current plan (the API answers 403, #187). Until they are, merge with the
guarded script instead of the GitHub button or a plain `gh pr merge`:

```bash
scripts/merge_pr.py 123 --dry-run          # only check
scripts/merge_pr.py 123 --delete-branch    # check, then squash-merge
```

It refuses - and lists why - unless the pull request is open, not a draft, targets `main`, has no
conflicts, every required check ran and passed on its current head commit, and no other check
failed. It then squash-merges pinned to that head commit (`--match-head-commit`), so a commit
pushed in between cannot slip in unverified, and it never uses `--admin`. It needs the GitHub CLI
(`gh`) logged in, and Python 3.9 or later (standard library only).

## When a check is red

- **Read the failing job's log first.** Fix the cause in the pull request; a re-run without a
  change is only for a failure that is clearly not caused by the code (see the next point).
- **"The job was not started"** (the job failed within seconds, with no steps): GitHub did not run
  it, usually because of a failed payment or a spending limit. That is what happened on #180, #182
  and #185, which were merged without any check having run (#187). Fix it in the account's
  *Billing & plans* settings, then re-run the jobs. The code has not been checked until they pass.
- **`test`**: run `cd backend && ./mvnw verify` locally (needs Docker). Fix the code or the test.
  A coverage failure means testing the behaviour you changed; never loosen a limit to pass
  (development standards, "Coverage is gated").
- **`sql-lint`**: lint the migration locally (see the development standards). Never edit an
  already-applied migration to satisfy it; that one is in `.sqlfluffignore` for a reason.
- **`semgrep`**: fix the finding. A false positive gets an inline `nosemgrep` with the reason, in
  a pull request a reviewer can see.
- **`gitleaks`**: rotate the credential and remove it from the change; never rewrite history or
  exclude the path to silence it.
- **`scripts`**: the merge gate's tests or its consistency checks failed - usually a workflow's
  job name or trigger changed without updating `REQUIRED_CHECKS` and the ruleset.

## When main is red

The **Main health** workflow watches every workflow above on `main`. When one goes red it opens
an issue titled `[CI] main is red: <workflow>` (label `ci-red`), comments on it for further red
runs, and closes it once the workflow is green on `main` again. While such an issue is open, fix
`main` before merging anything else: every pull request is checked against it.

## Turning on the hard gate

Once the repository can use rulesets (GitHub Pro or Team, or a public repository), apply the
prepared ruleset; it requires a pull request and the checks above on `main`, forbids force pushes
and deleting `main`, and lets no one bypass it:

```bash
gh api -X POST repos/{owner}/{repo}/rulesets --input .github/rulesets/main.json
```

Then prove it, as #187's Definition of Done asks: open a draft pull request with a deliberately
failing test, wait for `test` to fail, and check that GitHub refuses the merge. Close it
afterwards. The script stays useful after that, as the check-first way to merge.
