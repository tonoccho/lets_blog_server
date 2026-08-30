---
name: implement-issue
description: Implement a GitHub Issue that has been explicitly marked Ready. Use this skill to analyze the repository, create an implementation plan, implement the change, and validate it.
model: opus
---

# Implement Issue

You are starting the implementation workflow.

Only implement Issues that are explicitly in the `Ready` state.

Delegate implementation work to the `implementer` agent.

---

## Step 1: Select the Issue

If the user specified an Issue number:

Use that Issue.

If no Issue number was specified:

Search GitHub for Issues with:

`Status = Ready`

Do not select:

- Inbox
- Backlog
- In Progress
- Review
- Done

If multiple Ready Issues exist:

Select according to:

1. Highest priority
2. Dependency readiness
3. Oldest Ready Issue

Before implementing, report which Issue was selected.

---

## Step 2: Verify readiness

Read the complete Issue.

Verify:

- Goal
- Requirements
- Acceptance Criteria
- Scope
- Out of Scope
- Dependencies

If the Issue is no longer implementation-ready:

Stop.

Return the Issue to Backlog and explain why.

Do not guess missing requirements.

---

## Step 3: Update status

Change:

`Ready → In Progress`

Record the Issue number being worked on.

---

## Step 4: Create the working branch

Invoke the `git-workflow` skill to:

1. Inspect repository state (stop if there are unrelated uncommitted changes).
2. Determine and update the base branch (this project branches from `develop`, not `main`).
3. Create the Issue branch (`<type>/<issue-number>-<short-description>`).

Do not invoke the `implementer` agent until the correct branch is checked out and confirmed.

---

## Step 5: Invoke implementer

Ask the `implementer` agent to:

1. Read the Issue.
2. Inspect the codebase.
3. Analyze impact.
4. Create an implementation plan.
5. Implement the plan.
6. Add or update tests.
7. Run validation.
8. Report the final result.

---

## Step 6: Validate implementation result

Check the implementer's report.

Verify that:

- Tests were actually run.
- Failed tests are not hidden.
- Acceptance criteria were checked.
- No unrelated scope expansion occurred.
- The working tree is understood.

Do not trust a statement such as "all tests pass" without evidence from the tool output.

---

## Step 7: Commit and push

Invoke the `git-workflow` skill to:

1. Inspect the diff and confirm every change belongs to this Issue.
2. Run project validation (formatter, lint, typecheck, tests) if not already confirmed in Step 6.
3. Commit with a message following the repository's convention.
4. Push the branch (`git push -u origin <branch-name>`).

Do not skip this step — implementation is not usable by later stages (Review, QA, Pull Request) until it is committed and pushed.

---

## Step 8: Move to Review

If implementation is complete and validation is successful:

Change:

`In Progress → Review`

Do not move directly to Done.

If implementation is blocked:

Keep:

`In Progress`

and report the blocker.

---

## Output

Return:

### Issue

Issue number and title.

### Status

Current GitHub status.

### Implementation Summary

Short summary.

### Tests

Commands executed and results.

### Acceptance Criteria

PASS / FAIL / NOT VERIFIED.

### Unrelated Issues Filed

Any new Issue numbers created in `Inbox` for unrelated problems discovered during implementation. If none:

`None`

### Next Step

Usually:

`Review`

---

## Rules

Never implement an Issue that is not Ready.

Never mark an Issue Done.

Never silently change requirements.

Never fix unrelated problems discovered during implementation.

If unrelated problems are discovered, immediately create a separate Issue in `Inbox` for each (using the `plan-issue` template) — do not ask the user for judgment on whether to file it. Set each Issue's `Priority` field (P0/P1/P2) before considering it filed; never leave priority unset. Report the new Issue number(s) in the final output.