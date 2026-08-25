---
name: work-next
description: Select the highest-priority Ready GitHub Issue and drive it through implementation, review, and QA. Use this skill when the user asks Claude Code to find the next piece of work or continue the development workflow.
---

# Work Next

You are the orchestrator of the project's AI development workflow.

Your responsibility is to select the next Ready GitHub Issue and move it through the development pipeline.

You do not directly implement application code.

You coordinate:

- implement-issue
- review-issue
- qa-issue

The GitHub Issue status is the source of truth.

---

# Workflow

## Step 1: Inspect the repository

Before selecting work:

1. Read `CLAUDE.md`.
2. Inspect the Git repository status.
3. Inspect the GitHub Project / Issues.
4. Identify Issues currently in `Ready`.

Do not start implementation if the working tree contains unexpected changes that could interfere with the selected Issue.

If there are unrelated uncommitted changes:

Stop and report them.

---

# Step 2: Find Ready Issues

Search GitHub for Issues with:

`Status = Ready`

Ignore Issues in:

- Inbox
- Backlog
- In Progress
- Review
- QA
- Done

---

# Step 3: Select the next Issue

If multiple Ready Issues exist, prioritize them using:

1. Explicit project priority
2. Blocking dependencies
3. Dependency readiness
4. Issue age

Do not choose an Issue merely because it appears first in the search results.

Before proceeding, report:

- Issue number
- Title
- Priority
- Why it was selected

---

# Step 4: Verify readiness

Read the complete Issue.

Confirm:

- Goal is clear
- Requirements are clear
- Acceptance criteria are testable
- Scope is bounded
- Dependencies are resolved
- No blocking questions remain

If the Issue is not actually Ready:

Do not implement it.

Move it back to `Backlog` if appropriate and explain why.

---

# Step 5: Implement

Invoke the `implement-issue` skill.

The implementation workflow must:

1. Change the Issue to `In Progress`.
2. Invoke the `implementer` agent.
3. Investigate the codebase.
4. Create an implementation plan.
5. Implement the change.
6. Run tests.
7. Verify acceptance criteria.

If implementation fails:

Stop.

Do not continue to review.

---

# Step 6: Review

If implementation succeeds and the Issue reaches `Review`:

Invoke the `review-issue` skill.

The reviewer must independently inspect:

- Original Issue
- Acceptance Criteria
- Git diff
- Changed files
- Architecture
- Tests

---

# Step 7: Handle review result

If the reviewer returns:

`APPROVED`

continue to QA.

If the reviewer returns:

`CHANGES REQUIRED`

the Issue should return to:

`In Progress`

Then stop this workflow.

Do not automatically retry implementation unless explicitly configured to do so.

If the reviewer returns:

`REQUIREMENT CLARIFICATION`

return the Issue to:

`Backlog`

and stop.

---

# Step 8: QA

If review is approved:

Invoke the `qa-issue` skill.

QA must verify the actual behavior against the acceptance criteria.

---

# Step 9: Handle QA result

If QA returns:

`PASS`

change:

`QA → Done`

If QA returns:

`FAIL`

change:

`QA → In Progress`

Then stop.

If QA returns:

`BLOCKED`

keep the Issue in:

`QA`

Then stop.

---

# Step 10: Final report

Report:

## Completed Issue

- Issue number
- Title

## Workflow

```text
Ready
→ In Progress
→ Review
→ QA
→ Done