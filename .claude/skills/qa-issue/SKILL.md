---
name: qa-issue
description: Validate a reviewed GitLab Issue from the user's perspective. Verify acceptance criteria, user-visible behavior, edge cases, errors, and regressions. Use this skill when an Issue is in QA.
model: sonnet
---

# QA Issue

You are starting the QA workflow.

Only test Issues in the `QA` state.

Delegate behavioral validation to the `qa` agent.

The QA agent must not modify production code.

---

## Step 1: Select the Issue

If the user specified an Issue:

Use that Issue.

Otherwise find Issues with:

`Status = QA`

Prefer:

1. Highest priority
2. Oldest QA Issue

---

## Step 2: Read the requirements

Read the complete GitLab Issue.

Extract every acceptance criterion.

Each criterion must become an independently verifiable test.

Do not collapse multiple criteria into one vague test.

---

## Step 3: Invoke QA agent

Ask the `qa` agent to verify:

- Happy path
- Edge cases
- Error handling
- Regression behavior
- Acceptance criteria
- User-visible behavior

The QA agent must distinguish:

`Executed`

from:

`Code inspected`

from:

`Not verified`

---

## Step 4: Evaluate result

### PASS

All required acceptance criteria are verified.

No significant regression is found.

Proceed to Merge Request creation (Step 5a) — do not move straight to Done. The merge is `complete-issue`'s job, not this skill's.

### FAIL

A required behavior does not work.

Return to implementation.

### BLOCKED

The environment prevents meaningful validation.

Keep the Issue in QA and report exactly what is missing.

---

## Step 5: Update status

If FAIL:

`QA → In Progress`

If BLOCKED:

remain:

`QA`

If PASS: do not change status yet — continue to Step 5a first.

---

## Step 5a: Create the Merge Request (PASS only)

QA passing means the work is behaviorally correct, not that it is done — nothing has been merged yet.

Invoke the `pull-request` skill to open (or confirm an existing) Merge Request for the Issue's branch.

The Issue's `status::` label remains:

`QA`

Do not move it to `Done` here. `Done` is reserved for after the Merge Request is actually merged — see the `complete-issue` skill.

Report the Merge Request URL to the user. Then stop this workflow and return to the caller — `work-next` invokes `complete-issue`, which merges the Merge Request and finalizes the Issue. Never merge from this skill.

---

## Step 6: Record evidence

Add a concise QA report to the Issue or PR.

Include:

- Test scenarios
- Commands
- Results
- Acceptance criteria
- Known limitations

---

## Output

### QA Result

PASS / FAIL / BLOCKED

### Scenarios

List tested scenarios.

### Acceptance Criteria

PASS / FAIL / NOT VERIFIED

### Unrelated Issues Filed

New Issue numbers created in `Inbox` for unrelated problems noticed during QA. If none:

`None`

### Evidence

Explain how the result was established.

### Status

Current `status::` label.

### Next Step

`Awaiting complete-issue (merge)` (PASS) / `Implementation` (FAIL) / `Blocked`

If PASS, include the Merge Request URL from Step 5a and state that the Issue moves to `Done` via `complete-issue`, which performs the squash merge.

---

## Rules

Never claim UI or end-to-end behavior was verified unless it was actually tested.

Never ignore failed acceptance criteria.

Never modify production code.

Do not mark Done merely because automated tests pass.

Never merge a Merge Request from this skill — merging belongs to `complete-issue`.

Never move an Issue to `Done` from this skill — `Done` requires a confirmed merge, handled by `complete-issue`.

The user's expected behavior is the final authority.
