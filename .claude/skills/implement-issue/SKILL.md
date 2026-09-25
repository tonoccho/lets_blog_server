---
name: implement-issue
description: Implement a GitLab Issue that has been explicitly marked Ready. Use this skill to analyze the repository, create an implementation plan, implement the change, and validate it.
model: sonnet
---

# Implement Issue

You are starting the implementation workflow.

Only implement Issues that are explicitly in the `Ready` state.

Delegate implementation work to the `implementer` agent.

---

## Step 1: Select the Issue

Use the Issue number if the user specified one. Otherwise search for `status::Ready` Issues
only (never Inbox/Backlog/In Progress/Review/Done), and if multiple exist, select by highest
priority, then dependency readiness, then oldest Ready Issue.

Before implementing, report which Issue was selected.

---

## Step 2: Verify readiness

Read the complete Issue, **including its existing comments**. Verify Goal, Requirements,
Acceptance Criteria, Scope, Out of Scope, and Dependencies — per **Dependency Resolution** in
`CLAUDE.md`, judging from the live dependency status `work-next` Step 4 already gathered, not
from the Issue body's prose or an earlier comment.

If the Issue is no longer implementation-ready, stop, return it to Backlog, and explain why,
citing the live evidence you checked. Before posting a rollback that contradicts a recent Ready
promotion, apply the same guard as `work-next` Step 4: re-check each ground the promotion cited
and name the one that no longer holds; if they all still hold, apply the `CLAUDE.md` definition
instead of posting a contradicting verdict (#751). Do not guess missing requirements.

---

## Step 3: Update status

Change `Ready → In Progress`. Record the Issue number being worked on.

---

## Step 4: Create the working branch

Invoke the `git-workflow` skill to inspect repository state (stop if there are unrelated
uncommitted changes), update the base branch (this project branches from `develop`, not
`main`), and create the Issue branch (`<type>/<issue-number>-<short-description>`). Do not
invoke the `implementer` agent until the branch is checked out and confirmed.

---

## Step 5: Invoke implementer

Ask the `implementer` agent to read the Issue, inspect the codebase, analyze impact, plan
(including which Acceptance Criterion becomes which Gherkin scenario), and implement it,
working **test-first** per `CLAUDE.md` → **Test-First Implementation** (the single definition
of RED/GREEN order, phase separation, and commit convention — do not restate it here). It
reports per its Final Output format (`implementer.md`), including red evidence and coverage.

---

## Step 6: Validate implementation result

Check the implementer's report. Verify that tests were actually run, failed tests are not
hidden, acceptance criteria were checked, no unrelated scope expansion occurred, and the
working tree is understood.

Also verify the test-first evidence — this is a gate, not a formality:

- **Red evidence exists** for every Acceptance Criterion implemented: the failing scenario
  names and the command that produced the failure. A criterion implemented without a prior
  failing test is a failed stage; send it back.
- **Coverage** of the production code this Issue changed meets the bar in `CLAUDE.md` →
  **Test-First Implementation** → **Coverage**, with the measured numbers and command. A report
  without numbers is not verified.
- **No test was skipped, ignored, deleted, or weakened** — see `CLAUDE.md` →
  **Test-First Implementation** → **Never skip a test** for the forbidden forms. Check the diff
  for these directly.

Phase separation is not re-checked here: `guard.py` → `check_commit_phase` refuses a mixed
commit at `git commit` time, and `scripts/git-hooks/pre-commit` enforces the same invariant for
any committer — a mixed-phase commit cannot exist in the history this step would inspect.

Do not trust a statement such as "all tests pass" without evidence from the tool output.

---

## Step 7: Push

The `implementer` agent already committed each phase separately in Step 5. Invoke
`git-workflow` to push the branch (`git push -u origin <branch-name>`) — implementation is not
usable by later stages (Review, QA, Merge Request) until it is pushed.

---

## Step 8: Move to Review

If implementation is complete and validation is successful, change `In Progress → Review`. Do
not move directly to Done. If implementation is blocked, keep `In Progress` and report the
blocker.

---

## Output

Carry forward the `implementer` agent's Final Output (Summary, Red Evidence, Coverage,
Acceptance Criteria) rather than regenerating it. Add only what this layer knows that the
agent's report does not: Issue number and title, current `status::` label, any new Issue
numbers filed to `Inbox` for unrelated problems (`None` if none), and the Next Step (usually
`Review`).

---

## Rules

Never implement an Issue that is not Ready. Never mark an Issue Done. Never silently change
requirements. Never violate `CLAUDE.md` → **Test-First Implementation** (production before red,
mixed-phase edits, or a test made green by skipping/weakening it). Never fix unrelated problems
discovered during implementation — immediately create a separate Issue in `Inbox` for each
(the `plan-issue` template) with `Priority` set, and report the new Issue number(s).