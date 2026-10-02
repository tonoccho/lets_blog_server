---
name: review-issue
description: Independently review an implementation against its GitLab Issue, acceptance criteria, architecture, code quality, and regression risks. Use this skill when an Issue is in Review.
model: sonnet
---

# Review Issue

You are starting the code review workflow.

Only review Issues in the `Review` state.

Delegate the actual review to the `reviewer` agent.

The reviewer must not modify production code.

---

## Step 1: Select the Issue

If the user specified an Issue:

Use that Issue.

Otherwise find Issues with:

`Status = Review`

If multiple Issues are available:

Prefer:

1. Highest priority
2. Oldest Review Issue

---

## Step 2: Gather evidence

Read:

- GitLab Issue
- Acceptance Criteria
- Implementation Plan
- Git diff
- Changed files
- Relevant architecture documentation
- Relevant tests

Do not review based only on the implementation summary.

---

## Step 3: Invoke reviewer

**サブエージェントの起動(#1269)**: `Agent` ツールに `run_in_background` を付けず、フォアグラウンドで起動する。
結果を受け取るまでターンを終えない。残りのワークフローを汎用エージェントなどのバックグラウンドのエージェントに渡さない。
無人実行(`claude -p`)では、待機を宣言してターンを終えた時点で段階が終わり、再開が1回消費される。

Ask the `reviewer` agent to independently evaluate:

### Requirements

Does the implementation satisfy the Issue?

### Architecture

Does it follow the project's architecture?

### Code quality

Is it maintainable and appropriately simple?

### Regression

Could existing functionality be broken?

### Tests

Are the tests sufficient?

### Test-first process

Was the implementation actually test-first, per `CLAUDE.md` → **Test-First Implementation**?
The reviewer verifies this from the branch history and the diff:

- Gherkin acceptance tests exist for the Acceptance Criteria (or the report states why a
  criterion could not be reached through the web UI).
- Red evidence was reported for each criterion.
- No commit mixes test and production paths.
- The changed production code reaches 90% C1 and 90% C2, as measured numbers.
- No test was skipped, ignored, deleted, or weakened to make a run green.

Any of these failing is `CHANGES REQUIRED`, not an advisory note.

### Scope

Did implementation exceed the Issue?

### Unrelated findings

Any pre-existing problem noticed that is unrelated to this Issue. File it as a new Issue in `Inbox` immediately, without asking the user first. Set its `Priority` field (P0/P1/P2) before considering it filed; never leave priority unset.

---

## Step 4: Classify result

The reviewer must return:

### APPROVED

No blocking or important problems.

Proceed to QA.

### CHANGES REQUIRED

One or more blocking or important problems exist.

Return the Issue to implementation.

### REQUIREMENT CLARIFICATION

The Issue itself is insufficient to determine the correct behavior.

Return the Issue to Backlog.

---

## Step 5: Update the `status::` label

If:

`APPROVED`

change:

`Review → QA`

If:

`CHANGES REQUIRED`

change:

`Review → In Progress`

Add the review findings to the Issue or PR.

If:

`REQUIREMENT CLARIFICATION`

change:

`Review → Backlog`

Document the ambiguity.

---

## Output

### Review Result

APPROVED / CHANGES REQUIRED / REQUIREMENT CLARIFICATION

### Findings

List findings with severity:

- BLOCKING
- IMPORTANT
- SUGGESTION

### Acceptance Criteria

PASS / FAIL / UNCERTAIN

### Unrelated Issues Filed

New Issue numbers created in `Inbox` for unrelated problems noticed during review. If none:

`None`

### Status Change

Explain the `status::` label transition.

### Next Step

State exactly what should happen next.

---

## Rules

Do not modify production code.

Do not approve without comparing against the original Issue.

Do not treat stylistic preferences as blocking issues.

Do not invent problems.

The reviewer must remain independent from the implementation process.