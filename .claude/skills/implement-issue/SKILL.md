---
name: implement-issue
description: Implement a GitHub Issue that has been explicitly marked Ready. Use this skill to analyze the repository, create an implementation plan, implement the change, and validate it.
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

## Step 4: Invoke implementer

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

## Step 5: Validate implementation result

Check the implementer's report.

Verify that:

- Tests were actually run.
- Failed tests are not hidden.
- Acceptance criteria were checked.
- No unrelated scope expansion occurred.
- The working tree is understood.

Do not trust a statement such as "all tests pass" without evidence from the tool output.

---

## Step 6: Move to Review

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

### Next Step

Usually:

`Review`

---

## Rules

Never implement an Issue that is not Ready.

Never mark an Issue Done.

Never silently change requirements.

Never fix unrelated problems discovered during implementation.

If unrelated problems are discovered, recommend separate Issues.