---
name: work-next
description: Select the highest-priority Ready GitHub Issue and drive it through branch creation, implementation, review, QA, and Pull Request creation. Use this skill when the user asks Claude Code to find the next piece of work, implement the next task, or continue the development workflow (e.g. "次のタスクを実装して").
---

# Work Next

You are the orchestrator of the project's AI development workflow.

Your responsibility is to select the next Ready GitHub Issue and drive it all the way to an open Pull Request awaiting the user's merge.

You do not directly implement application code.

You coordinate:

- git-workflow (branch creation, commit, push)
- implement-issue
- review-issue
- qa-issue
- pull-request

Finishing the Issue (`Done` + branch cleanup) happens separately, via `complete-issue`, once the user confirms the Pull Request was merged. This skill's run ends when the Pull Request is opened — it does not wait for the merge.

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
2. Create the working branch from `develop` via `git-workflow`.
3. Invoke the `implementer` agent.
4. Investigate the codebase.
5. Create an implementation plan.
6. Implement the change.
7. Run tests.
8. Verify acceptance criteria.
9. Commit and push via `git-workflow`.

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

The `qa-issue` skill itself invokes `pull-request` to open the Pull Request. The Issue status remains `QA` — do not move it to `Done` here. Report the Pull Request URL and ask the user to review and merge it. Then stop; this workflow's job is done once the PR is open.

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

## Issue

- Issue number
- Title

## Workflow so far

```text
Ready
→ In Progress
→ Review
→ QA
→ Pull Request opened (awaiting merge)
```

## Next Step

Tell the user: once the Pull Request is merged, say so (e.g. "PRをマージしました") to trigger `complete-issue`, which moves the Issue to `Done` and deletes the working branch locally and remotely.

---

# Rules

Never mark an Issue `Done` from this skill — that requires a confirmed merge via `complete-issue`.

Never skip branch creation (`git-workflow`) before invoking the `implementer` agent.

Never create a Pull Request before QA has passed.

If any stage stops the workflow (implementation failure, changes required, requirement clarification, QA failure, QA blocked), do not silently continue to the next stage.