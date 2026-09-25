---
name: merge-request
description: Standard workflow for reviewing branch changes and creating high-quality GitLab Merge Requests linked to the originating Issue.
model: sonnet
---

# Merge Request Skill

## Purpose

Use this skill when implementation for a GitLab Issue is complete and the work is ready to be submitted for review.

A Merge Request represents a reviewable unit of work.

The Merge Request should make it easy for another developer to understand:

- What changed?
- Why did it change?
- How was it tested?
- Which Issue does it resolve?
- Are there known risks or limitations?

---

# Preconditions

Do not create a Merge Request until all of the following are true:

- The selected Issue is implemented.
- Acceptance Criteria are satisfied.
- Required validation has passed.
- The working branch has been pushed.
- No known critical or high-severity problem remains.
- The branch contains no unrelated changes.
- The branch merges cleanly into `develop` — resolve any conflict first (see below), do not
  report it as a blocker.

If any precondition is not met, do not create the Merge Request.

---

# 1. Determine the Merge Request Range

**The base branch is `develop`. Never `main`.**

`develop` is this repository's integration branch and the GitLab project's default branch.
`main` is the **release branch** — it is updated only by a deliberate release
(`develop` → `main`), never by an Issue's Merge Request.

Pass it explicitly:

```bash
glab mr create --target-branch develop --source-branch <working-branch> \
  --remove-source-branch --squash-before-merge --yes ...
```

`--base develop` is required even though `develop` is the default. Omitting it works until
someone's local `gh` is configured differently, or a branch was cut from `main` — and the
failure is silent: the PR is created against `main` and looks normal.

The Merge Request should be:

`<working-branch>` → `develop`

For example:

`feature/123-add-tag-search` → `develop`

Confirm that the current branch is not the base branch.

## Before creating the PR, verify the base (issue #839)

```bash
# 1. The working branch must be based on develop
git merge-base --is-ancestor origin/develop HEAD && echo "OK: developから派生している"

# 2. After creating it, confirm the PR's base really is develop
glab mr view <number> -F json --jq .target_branch   # => develop
```

**Why this check exists.** In #839, two Issue PRs were created with `base=main` by mistake.
Because their branches were cut from `develop`, squash-merging them into `main` pulled in
**the entire 159-commit difference** — one squash showed
`1744 files changed, 85710 insertions(+), 102813 deletions(-)`. `main` stopped pointing at the
released version, and the mistake was invisible in the PR itself: the diff looked enormous but
the PR page gave no warning.

An unexpectedly huge diff on what should be a small change is the symptom. If you see it,
**check the base before merging.** This matters more now that `complete-issue` merges
automatically: the base check here is the last human-legible chance to catch a wrong base
before the squash lands.

## Sync with `develop` and resolve conflicts

Before creating the Merge Request, bring the branch up to date with `develop`:

```bash
git fetch origin
git merge origin/develop
```

If it conflicts, **resolve it** — a conflict with `develop` is not a blocker here. Follow
`CLAUDE.md` → **Merge Conflicts**: resolve the conflicted files, commit the resolution, re-run
the full relevant validation (tests, lint, type check), and push.

If validation fails after the resolution:

1. Fix the production code first — a resolution most often drops or duplicates a change.
2. Change a test only when the test case itself is demonstrably inappropriate, and say which
   and why in the Merge Request body.
3. Never make it green by skipping, ignoring, or deleting a test.

The conflict-resolution commit itself may touch test and production files together; it
reconciles two existing histories rather than authoring new behavior. Anything written
**after** it returns to alternating test and production phases in separate commits.

---

# 2. Inspect the Final Diff

Before creating the Merge Request, inspect the complete branch diff against the base branch.

Verify:

- Every change belongs to the selected Issue.
- No accidental files are included.
- No debugging code remains.
- No secrets or credentials are included.
- No generated files are included unless expected.
- The implementation matches the Issue.

Review the diff as if reviewing another developer's Merge Request.

---

# 3. Verify Acceptance Criteria

Compare the final implementation directly against the Issue.

For each Acceptance Criterion, determine:

- PASS
- FAIL
- NOT APPLICABLE

Do not create the Merge Request while any required criterion is `FAIL`.

Do not claim completion based only on the existence of code.

Behavior must satisfy the intended outcome.

---

# 4. Merge Request Title

Follow the repository's existing convention.

If no convention exists:

`<type>: <summary>`

Examples:

- `feat: add article tag search`
- `fix: prevent login failure on empty response`
- `refactor: simplify article service`
- `docs: update local development guide`

The title should describe the user-visible or architectural purpose of the change.

Because merges are squashed, this title becomes the commit subject on `develop`. Write it as
the commit message you want in the history.

Avoid vague titles such as:

- `fix stuff`
- `updates`
- `changes`
- `work on issue`

---

# 5. Merge Request Description

Use the following structure:

## Summary

Briefly explain what changed.

## Why

Explain the problem or reason for the change.

## Changes

- Main change
- Main change
- Main change

## Testing

- [x] Formatter
- [x] Lint
- [x] Type check
- [x] Unit tests
- [ ] Integration tests (not applicable)

## Related Issue

Closes #<issue-number>

## Risks / Notes

Describe known risks, limitations, migration requirements, or write `None`.

Only mark tests as complete when they were actually executed successfully.

If a validation category is not applicable, state why.

Do not fabricate test results.

---

# 6. Link the Issue

The Merge Request must link to the originating Issue.

Use:

`Closes #<issue-number>`

when the PR is intended to resolve the Issue when merged.

If the PR intentionally does not completely resolve the Issue, do not use a closing keyword.

Instead use:

`Related to #<issue-number>`

The relationship must accurately reflect the work.

---

# 7. Create the Merge Request

Create the Merge Request with `glab mr create` (see the command above).

Confirm:

- Correct source branch
- Correct target branch
- Correct title
- Complete description
- Correct Issue link

Do not create duplicate Merge Requests for the same branch.

Before creating a new PR, check whether an open PR already exists for the branch.

If one exists:

- Do not create another.
- Report the existing Merge Request.

---

# 8. Confirm Creation

After creation, confirm:

- Merge Request number
- Merge Request URL
- Source branch
- Target branch

Do not claim the Merge Request was created unless the creation was confirmed.

---

# 9. Update Issue Status

This skill is normally invoked from `qa-issue` Step 5a, after the Issue has already passed internal Review and QA. In that case:

Do not change the Issue's `status::` label. It should already be `status::QA`, and stays there — the merge and the move to `Done` belong to `complete-issue`, which runs after this skill.

If this skill is ever invoked standalone, before internal Review/QA have run, do not move the Issue to:

- `Done`
- `Closed`
- `Complete`

PR creation means:

`Ready for review`

not:

`Finished and merged`

Moving the Issue to `Done` happens only via the `complete-issue` skill, which merges the Merge Request and then finalizes the Issue.

Never merge the Merge Request from this skill. Creating it and merging it are deliberately separate steps.

---

# 10. Report

Report the result in this format:

Merge Request Created

Issue:
#<issue-number> <issue-title>

Branch:
<working-branch>

Base:
<base-branch>

Merge Request:
#<pr-number>
<pr-url>

Validation:
- formatter: PASS
- lint: PASS
- typecheck: PASS / NOT APPLICABLE
- unit tests: PASS
- integration tests: PASS / NOT APPLICABLE

Issue Status:
QA (awaiting `complete-issue`)

---

# Failure Handling

If Merge Request creation fails:

- Do not falsely report success.
- Keep the branch intact.
- Do not delete commits.
- Do not reset the repository.
- Report the failure.
- Include the recommended next action.

Example:

Merge Request creation failed.

Branch:
feature/123-add-tag-search

The branch was successfully pushed.

Recommended next action:
Investigate `glab` authentication (`glab auth status`) or project permissions.

---

# Completion Criteria

The Merge Request workflow is complete only when:

- The PR exists and its creation was confirmed.
- The PR points to the correct base branch.
- The correct working branch is used.
- The Issue relationship is documented.
- The PR description accurately describes the work.
- Validation results are accurately reported.
- The Issue's status is left at `QA`, awaiting `complete-issue` (which merges the PR and moves it to `Done`).
