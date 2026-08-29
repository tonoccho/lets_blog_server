---
name: pull-request
description: Standard workflow for reviewing branch changes and creating high-quality GitHub Pull Requests linked to the originating Issue.
model: sonnet
---

# Pull Request Skill

## Purpose

Use this skill when implementation for a GitHub Issue is complete and the work is ready to be submitted for review.

A Pull Request represents a reviewable unit of work.

The Pull Request should make it easy for another developer to understand:

- What changed?
- Why did it change?
- How was it tested?
- Which Issue does it resolve?
- Are there known risks or limitations?

---

# Preconditions

Do not create a Pull Request until all of the following are true:

- The selected Issue is implemented.
- Acceptance Criteria are satisfied.
- Required validation has passed.
- The working branch has been pushed.
- No known critical or high-severity problem remains.
- The branch contains no unrelated changes.

If any precondition is not met, do not create the Pull Request.

---

# 1. Determine the Pull Request Range

Determine:

- Base branch
- Current working branch

The Pull Request should normally be:

`<working-branch>` → `<base-branch>`

For example:

`feature/123-add-tag-search` → `main`

Confirm that the current branch is not the base branch.

---

# 2. Inspect the Final Diff

Before creating the Pull Request, inspect the complete branch diff against the base branch.

Verify:

- Every change belongs to the selected Issue.
- No accidental files are included.
- No debugging code remains.
- No secrets or credentials are included.
- No generated files are included unless expected.
- The implementation matches the Issue.

Review the diff as if reviewing another developer's Pull Request.

---

# 3. Verify Acceptance Criteria

Compare the final implementation directly against the Issue.

For each Acceptance Criterion, determine:

- PASS
- FAIL
- NOT APPLICABLE

Do not create the Pull Request while any required criterion is `FAIL`.

Do not claim completion based only on the existence of code.

Behavior must satisfy the intended outcome.

---

# 4. Pull Request Title

Follow the repository's existing convention.

If no convention exists:

`<type>: <summary>`

Examples:

- `feat: add article tag search`
- `fix: prevent login failure on empty response`
- `refactor: simplify article service`
- `docs: update local development guide`

The title should describe the user-visible or architectural purpose of the change.

Avoid vague titles such as:

- `fix stuff`
- `updates`
- `changes`
- `work on issue`

---

# 5. Pull Request Description

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

The Pull Request must link to the originating Issue.

Use:

`Closes #<issue-number>`

when the PR is intended to resolve the Issue when merged.

If the PR intentionally does not completely resolve the Issue, do not use a closing keyword.

Instead use:

`Related to #<issue-number>`

The relationship must accurately reflect the work.

---

# 7. Create the Pull Request

Create the Pull Request using the configured GitHub workflow.

Confirm:

- Correct source branch
- Correct target branch
- Correct title
- Complete description
- Correct Issue link

Do not create duplicate Pull Requests for the same branch.

Before creating a new PR, check whether an open PR already exists for the branch.

If one exists:

- Do not create another.
- Report the existing Pull Request.

---

# 8. Confirm Creation

After creation, confirm:

- Pull Request number
- Pull Request URL
- Source branch
- Target branch

Do not claim the Pull Request was created unless the creation was confirmed.

---

# 9. Update Issue Status

This skill is normally invoked from `qa-issue` Step 5a, after the Issue has already passed internal Review and QA. In that case:

Do not change the Issue's GitHub status. It should already be `QA`, and stays `QA` — a human still needs to merge the Pull Request.

If this skill is ever invoked standalone, before internal Review/QA have run, do not move the Issue to:

- `Done`
- `Closed`
- `Complete`

PR creation means:

`Ready for review`

not:

`Finished and merged`

Moving the Issue to `Done` happens only via the `complete-issue` skill, after the user confirms the Pull Request was merged.

---

# 10. Report

Report the result in this format:

Pull Request Created

Issue:
#<issue-number> <issue-title>

Branch:
<working-branch>

Base:
<base-branch>

Pull Request:
#<pr-number>
<pr-url>

Validation:
- formatter: PASS
- lint: PASS
- typecheck: PASS / NOT APPLICABLE
- unit tests: PASS
- integration tests: PASS / NOT APPLICABLE

Issue Status:
QA (awaiting merge)

---

# Failure Handling

If Pull Request creation fails:

- Do not falsely report success.
- Keep the branch intact.
- Do not delete commits.
- Do not reset the repository.
- Report the failure.
- Include the recommended next action.

Example:

Pull Request creation failed.

Branch:
feature/123-add-tag-search

The branch was successfully pushed.

Recommended next action:
Investigate GitHub authentication or repository permissions.

---

# Completion Criteria

The Pull Request workflow is complete only when:

- The PR exists and its creation was confirmed.
- The PR points to the correct base branch.
- The correct working branch is used.
- The Issue relationship is documented.
- The PR description accurately describes the work.
- Validation results are accurately reported.
- The Issue's status is left at `QA`, awaiting the user's merge confirmation (`complete-issue` handles the move to `Done`).