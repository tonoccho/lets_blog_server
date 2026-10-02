---
name: work-next
description: Select the highest-priority Ready GitLab Issue and drive it through branch creation, implementation, review, QA, Merge Request creation, and merge. Use this skill when the user asks Claude Code to find the next piece of work, implement the next task, or continue the development workflow (e.g. "次のタスクを実装して").
model: sonnet
---

# Work Next

You are the orchestrator of the project's AI development workflow: select the next Ready
GitLab Issue and drive it all the way to a merged Merge Request and a `Done` Issue. You do not
implement application code directly — you coordinate `git-workflow`, `implement-issue`,
`review-issue`, `qa-issue`, `merge-request`, and `complete-issue` (squash merge, `Done`, branch
cleanup).

This skill's run ends when the Merge Request is merged and the Issue is `Done`, not when it is
opened. The GitLab Issue status is the source of truth.

---

# Workflow

## Step 1: Inspect the repository

Before selecting work, read `CLAUDE.md`, inspect the Git repository status, and inspect the
GitLab Issue board (`status::` labels) to identify Issues in `Ready`.

Stop and report unrelated uncommitted changes that could interfere with the selected Issue.

---

# Step 2: Find Ready Issues

`glab issue list --label 'status::Ready'`. Any other `status::` (Inbox, Backlog, In Progress,
Review, QA, Done) is out of scope here.

---

# Step 3: Select the next Issue

If multiple Ready Issues exist, rank them by `CLAUDE.md` → **Issue Provenance** → **Selection
order** — the single definition of the key order (`hotfix` first, then `user-request`, `bug`,
priority, blocking count, Issue number). Do not restate the key list here; read it live from
`CLAUDE.md` each time, since it is the one place it is defined. Never rank merely by
search-result order.

Read the labels; never infer `hotfix`, provenance, or bug-ness from an Issue's wording, and
never add or remove `hotfix` to steer the selection (CLAUDE.md → Issue Provenance → hotfix:
Claude reads it, never writes it).

Before proceeding, report the Issue number, title, priority, **whether it is `hotfix`**,
**whether it is `user-request`**, **whether it is `bug`**, and why it was selected.

---

# Step 4: Verify readiness

Read the complete Issue, **including its existing comments** — a readiness verdict already
posted on it is part of the input to this step, not noise to be re-derived around. Then run:

```bash
scripts/issue-dependency-status.sh <issue-number>
```

This prints the live state of every dependency and the readiness verdicts already posted.
Confirm:

- Goal is clear
- Requirements are clear
- Acceptance criteria are testable
- Scope is bounded
- Dependencies are resolved — per `CLAUDE.md` → **Dependency Resolution** → **What counts as a
  blocker**. Do not invent a second definition here.
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

This guard exists because of #751, where a Ready promotion and a Backlog rollback five minutes
apart were both factually correct — one judged by delivered code, the other by board status —
because neither read the other.

---

# Step 5: Write the acceptance tests first (RED)

Governed by `CLAUDE.md` → **Test-First Implementation** — the RED/GREEN order, phase
separation, and the service-level-test exception are defined there; do not restate them here.

`implement-issue` performs this work, on the Issue branch, immediately after `git-workflow`
creates it and before the `implementer` agent touches any production file. This skill's job is
to require the red evidence and to refuse an implementation report that lacks it. The Issue
branch must exist first — never write the scenarios on `develop`.

Do not continue to implementation until red is recorded for every criterion being implemented
in this cycle.

---

# Step 6: Implement

**サブエージェントの起動(#1269)**: `Agent` ツールに `run_in_background` を付けず、フォアグラウンドで起動する。
結果を受け取るまでターンを終えない。残りのワークフローを汎用エージェントなどのバックグラウンドのエージェントに渡さない。
無人実行(`claude -p`)では、待機を宣言してターンを終えた時点で段階が終わり、再開が1回消費される。

Invoke the `implement-issue` skill. It moves the Issue to `In Progress`, creates the working
branch, and drives the `implementer` agent through the full test-first cycle defined in
`CLAUDE.md` → **Test-First Implementation** (Step 5 above is that cycle's RED phase) —
including the C1/C2 coverage measurement, committing each phase separately, and pushing.

If implementation fails on a blocking requirement ambiguity only the user can resolve, stop and
report it — a genuine blocker, not a retryable failure. Otherwise (failing tests, an incomplete
step, a bug introduced during implementation) it is a recoverable stage outcome: address it and
re-invoke `implement-issue` automatically without pausing for the user (`CLAUDE.md` →
**Autonomous Task Execution**). After 3 failed attempts without reaching `Review`, stop and
report the unresolved blocker instead of retrying further.

---

# Step 7: Review

If implementation succeeds and the Issue reaches `Review`, invoke the `review-issue` skill. The
reviewer must independently inspect the original Issue, Acceptance Criteria, git diff, changed
files, architecture, and tests.

---

# Step 8: Handle review result

- `APPROVED` — continue to QA.
- `CHANGES REQUIRED` — Issue returns to `In Progress`. Re-invoke `implement-issue` automatically
  to address the findings, then return to Review. Do not stop to ask the user — retry
  automatically (`CLAUDE.md` → **Autonomous Task Execution**). After 3 consecutive
  `CHANGES REQUIRED` results without reaching `APPROVED`, stop and report the unresolved
  findings instead of retrying further.
- `REQUIREMENT CLARIFICATION` — Issue returns to `Backlog` and this workflow stops: a genuine
  blocker outside its authority, requiring the user (via `project-planner`) to resolve it.

---

# Step 9: QA

If review is approved, invoke the `qa-issue` skill to verify the actual behavior against the
acceptance criteria.

---

# Step 10: Handle QA result

- `PASS` — `qa-issue` itself invokes `merge-request` to open the Merge Request (Issue status
  still `QA`). Then invoke `complete-issue`: it merges (`glab mr merge --squash
  --remove-source-branch`), moves `status::QA → status::Done`, and cleans up the branch. Do not
  stop to ask the user — reaching `PASS` with an open Merge Request authorizes it (`CLAUDE.md`
  → **Autonomous Task Execution**). If `complete-issue` stops on a **merge conflict**, resolve
  it on the working branch per `CLAUDE.md` → **Merge Conflicts**, re-validate, push, and
  re-invoke `complete-issue` (counts against the same 3-attempt limit). If it stops for any
  other reason (draft, blocked merge state, Issue not in `QA`), do not work around it — report
  exactly which precondition failed and stop.
- `FAIL` — change `QA → In Progress`. Re-invoke `implement-issue` automatically to fix the
  failing scenario, then proceed back through Review and QA. Do not stop to ask the user —
  retry automatically (`CLAUDE.md` → **Autonomous Task Execution**). After 3 consecutive `FAIL`
  results without reaching `PASS`, stop and report the unresolved failure instead of retrying
  further.
- `BLOCKED` — keep the Issue in `QA` and stop. Verification cannot proceed (e.g. missing
  environment) — a genuine blocker, not a retryable failure.

---

# Step 11: Final report

Report:

## Issue

Issue number and title.

## Workflow so far

```text
Ready
→ In Progress
→ Acceptance tests written (RED, confirmed failing)
→ Implemented (GREEN)
→ Review
→ QA
→ Merge Request opened
→ Squash-merged
→ Done
```

## Tests

Carry forward the red evidence and the measured C1/C2 coverage numbers from
`implement-issue`'s report — do not regenerate them.

## Unrelated Issues Filed

Aggregate any new Issue numbers reported by `implement-issue`, `review-issue`, or `qa-issue`.
`None` if none.

## Merge

MR number/URL, squash-merge confirmation, branch cleanup result.

---

# Rules

- Never mark an Issue `Done` from this skill directly — `Done` is set by `complete-issue`, only
  after confirming the merge.
- Never skip branch creation (`git-workflow`) before invoking the `implementer` agent.
- Never accept a stage report that violates `CLAUDE.md` → **Test-First Implementation**
  (production before red evidence, missing/sub-90% C1/C2 coverage, or a test skipped/weakened
  to pass) — treat the stage as failed and re-invoke `implement-issue`; it is recoverable.
- Never create a Merge Request before QA has passed, and never merge one directly — delegate to
  `complete-issue`, which enforces the squash method and merge preconditions.
- Once this workflow starts an Issue, retry automatically after a recoverable stage outcome, up
  to that stage's retry limit (3 cycles), per `CLAUDE.md` → **Autonomous Task Execution** —
  which is also the single definition of genuine blockers (a merge conflict is not one; resolve
  it per **Merge Conflicts**).
- Any Issue filed to `Inbox` during this workflow must have its `Priority` field set — never
  leave it unset.
