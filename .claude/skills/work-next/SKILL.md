---
name: work-next
description: Select the highest-priority Ready GitHub Issue and drive it through branch creation, implementation, review, QA, Pull Request creation, and merge. Use this skill when the user asks Claude Code to find the next piece of work, implement the next task, or continue the development workflow (e.g. "次のタスクを実装して").
model: sonnet
---

# Work Next

You are the orchestrator of the project's AI development workflow.

Your responsibility is to select the next Ready GitHub Issue and drive it all the way to a merged Pull Request and a `Done` Issue.

You do not directly implement application code.

You coordinate:

- git-workflow (branch creation, commit, push)
- implement-issue
- review-issue
- qa-issue
- pull-request
- complete-issue (squash merge, `Done`, branch cleanup)

This skill's run ends when the Pull Request has been merged and the Issue is `Done`, not when the Pull Request is opened.

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

Read the complete Issue, **including its existing comments**. A readiness verdict already
posted on the Issue is part of the input to this step, not noise to be re-derived around.

Then run:

```bash
scripts/issue-dependency-status.sh <issue-number>
```

This prints the live state of every dependency the Issue records, and lists the readiness
verdicts already posted on it.

Confirm:

- Goal is clear
- Requirements are clear
- Acceptance criteria are testable
- Scope is bounded
- Dependencies are resolved — per **Dependency Resolution** in `CLAUDE.md`. Do not invent a
  second definition here. In particular: a dependency Issue that is open but named only in the
  body's prose is not by itself a blocker; what decides readiness is whether this Issue's
  acceptance criteria can be implemented and verified against the codebase as it stands.
- No blocking questions remain

If the Issue is not actually Ready:

Do not implement it.

Move it back to `Backlog` and explain why, citing the live evidence you checked.

## Before reversing a Ready promotion

If this step is about to move the Issue `Ready → Backlog` and a Ready promotion was posted on
it recently, do not post a rollback that merely restates your own reasoning. First:

1. Re-check each ground the promotion cited, live.
2. Name in the rollback comment which specific ground no longer holds.

If every ground still holds, the disagreement is about the definition of "resolved", not about
facts. Apply `CLAUDE.md` → **Dependency Resolution** and proceed; do not post a contradicting
verdict.

This guard exists because of #751: on #584, a Ready promotion (01:27Z) and a Backlog rollback
(01:32Z) were posted five minutes apart, and **both were factually correct**. One judged by the
code the dependencies had delivered; the other judged by the dependency Issues' board status.
Neither was reading the other, and this skill did not say which one governs.

---

# Step 5: Write the acceptance tests first (RED)

Before any production code changes, the Issue's Acceptance Criteria are turned into failing
Gherkin scenarios. This is governed by `CLAUDE.md` → **Test-First Implementation**; do not
apply a different order here.

`implement-issue` performs this work, on the Issue branch, immediately after `git-workflow`
creates it and before the `implementer` agent touches any production file. This skill's job is
to require the red evidence and to refuse an implementation report that lacks it.

What must happen:

1. The Issue branch exists first — never write the scenarios on `develop`.
2. Each Acceptance Criterion becomes one or more scenarios in
   `apps/web/e2e/features/**/*.feature` (Japanese keywords), with step definitions in
   `apps/web/e2e/steps/` and the right `@stage:` tag.
3. Run them: `cd apps/web && npm run test:at:fast` (or the stage project that applies).
4. **Confirm they fail, and capture the output.** A scenario that passes here does not exercise
   its criterion — fix the scenario, do not proceed.
5. Commit the test phase on its own (`test: …`). No production file is in that commit.

Do not continue to implementation until red is recorded for every criterion being implemented
in this cycle. If a criterion cannot be reached through the web UI, `implement-issue` must say
so explicitly and cover it with a service-level test — accept that only as a stated exception,
never as a silent omission.

---

# Step 6: Implement

Invoke the `implement-issue` skill.

The implementation workflow must:

1. Change the Issue to `In Progress`.
2. Create the working branch from `develop` via `git-workflow`.
3. Invoke the `implementer` agent.
4. Investigate the codebase.
5. Create an implementation plan.
6. Write the failing acceptance tests and record the red output (Step 5).
7. Change the production code — in a phase that touches no test file.
8. Run tests, and measure C1/C2 coverage of the changed production code (≥ 90%).
9. Verify acceptance criteria.
10. Commit each phase separately and push via `git-workflow`.

Items 6 and 7 alternate in small cycles and never merge into one edit, per `CLAUDE.md` →
**Test-First Implementation**. Item 6 is Step 5 of this skill; it is listed here because
`implement-issue` is what actually runs it.

If implementation fails:

If the failure is a blocking requirement ambiguity that only the user can resolve, stop and report it — this is a genuine blocker, not a retryable failure.

Otherwise (a fixable problem: failing tests, an incomplete step, a bug introduced during implementation), address it and re-invoke `implement-issue` automatically. Do not stop to ask the user whether to continue — this workflow does not pause between recoverable stages (see `CLAUDE.md` → Autonomous Task Execution).

Track implementation attempts for this Issue. After 3 failed attempts without reaching `Review`, stop and report the unresolved blocker instead of retrying further.

---

# Step 7: Review

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

# Step 8: Handle review result

If the reviewer returns:

`APPROVED`

continue to QA.

If the reviewer returns:

`CHANGES REQUIRED`

the Issue should return to:

`In Progress`

Re-invoke `implement-issue` automatically to address the reviewer's findings, then return to Review again. Do not stop to ask the user whether to continue — this workflow is explicitly configured to retry automatically (see `CLAUDE.md` → Autonomous Task Execution).

Track review cycles for this Issue. After 3 consecutive `CHANGES REQUIRED` results without reaching `APPROVED`, stop and report the unresolved findings instead of retrying further.

If the reviewer returns:

`REQUIREMENT CLARIFICATION`

return the Issue to:

`Backlog`

and stop. This is a genuine blocker outside this workflow's authority — it requires the user (via `project-planner`) to resolve the ambiguity, so do not retry automatically here.

---

# Step 9: QA

If review is approved:

Invoke the `qa-issue` skill.

QA must verify the actual behavior against the acceptance criteria.

---

# Step 10: Handle QA result

If QA returns:

`PASS`

The `qa-issue` skill itself invokes `pull-request` to open the Pull Request. The Issue status is still `QA` at that point.

Then invoke `complete-issue` for this Issue. It merges the Pull Request (`gh pr merge --squash --delete-branch`), moves the Issue `QA → Done`, and cleans up the branch. Do not stop to ask the user whether to merge — reaching `PASS` with an open Pull Request is what authorizes it (see `CLAUDE.md` → Autonomous Task Execution).

If `complete-issue` stops on a **merge conflict**, that is not a stop: resolve it on the
working branch per `CLAUDE.md` → **Merge Conflicts**, re-run validation, push, and re-invoke
`complete-issue`. If validation fails after the resolution, fix the production code first;
change a test only if the test case itself is demonstrably wrong, and never by skipping it.
Track these as implementation attempts under the same 3-attempt limit.

If `complete-issue` stops for any other reason (draft, blocked merge state, or the Issue is not
in `QA`), do not work around it. Report exactly which precondition failed, leave the Pull
Request open, and stop.

If QA returns:

`FAIL`

change:

`QA → In Progress`

Re-invoke `implement-issue` automatically to fix the failing scenario, then proceed back through Review and QA again. Do not stop to ask the user whether to continue — this workflow is explicitly configured to retry automatically (see `CLAUDE.md` → Autonomous Task Execution).

Track QA cycles for this Issue. After 3 consecutive `FAIL` results without reaching `PASS`, stop and report the unresolved failure instead of retrying further.

If QA returns:

`BLOCKED`

keep the Issue in:

`QA`

Then stop. `BLOCKED` means verification itself cannot proceed (e.g. missing environment, external dependency) — this is a genuine blocker, not a retryable failure.

---

# Step 11: Final report

Report:

## Issue

- Issue number
- Title

## Workflow so far

```text
Ready
→ In Progress
→ Acceptance tests written (RED, confirmed failing)
→ Implemented (GREEN)
→ Review
→ QA
→ Pull Request opened
→ Squash-merged
→ Done
```

## Tests

The red evidence (which scenarios failed, and the command that produced it) and the measured
C1/C2 coverage of the changed production code.

## Unrelated Issues Filed

Aggregate any new Issue numbers reported by `implement-issue`, `review-issue`, or `qa-issue` for unrelated problems discovered along the way. If none:

`None`

## Merge

Pull Request number and URL, the squash-merge confirmation, and the branch cleanup result.

---

# Rules

Never mark an Issue `Done` from this skill directly — `Done` is set by `complete-issue`, and only after it has confirmed the Pull Request is actually merged.

Never skip branch creation (`git-workflow`) before invoking the `implementer` agent.

Never let production code change before a failing acceptance test exists for the criterion it
implements. If an implementation report claims a change without red evidence for it, treat the
stage as failed and re-invoke `implement-issue` — it is a recoverable outcome, not a blocker.

Never accept an implementation report that lacks the measured C1/C2 coverage of the changed
production code, or that reports it below 90%.

Never accept a run made green by skipping, ignoring, or deleting a test.

Never create a Pull Request before QA has passed.

Never merge a Pull Request from this skill directly — always delegate to `complete-issue`, which enforces the squash method and the merge preconditions.

Once this workflow starts an Issue, do not pause to ask the user whether to continue after a recoverable stage outcome (implementation issues, Review `CHANGES REQUIRED`, QA `FAIL`) — retry automatically, up to that stage's retry limit (3 cycles), until the Issue either reaches a merged Pull Request or hits a genuine blocker.

Only stop short of a merged Pull Request for a genuine blocker: unresolved requirement ambiguity (`REQUIREMENT CLARIFICATION`, or a blocking question during implementation), QA `BLOCKED`, a retry limit exceeded, a Pull Request that cannot be merged as-is **for a reason other than a conflict** (a draft, a blocked merge state), or a live-system mutation requiring explicit user confirmation. When any of these stops the workflow, report it clearly rather than silently halting.

A merge conflict is not on that list. Resolve it on the working branch, re-validate, push, and continue (`CLAUDE.md` → **Merge Conflicts**).

Any Issue filed to `Inbox` during this workflow (by `implement-issue`, `review-issue`, or `qa-issue`) must have its `Priority` field (P0/P1/P2) set — never leave it unset.
