---
name: qa-issue
description: Validate a reviewed GitHub Issue from the user's perspective. Verify acceptance criteria, user-visible behavior, edge cases, errors, and regressions. Use this skill when an Issue is in QA.
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

Read the complete GitHub Issue.

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

Proceed to Done.

### FAIL

A required behavior does not work.

Return to implementation.

### BLOCKED

The environment prevents meaningful validation.

Keep the Issue in QA and report exactly what is missing.

---

## Step 5: Update status

If PASS:

`QA → Done`

If FAIL:

`QA → In Progress`

If BLOCKED:

remain:

`QA`

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

### Evidence

Explain how the result was established.

### Status

Current GitHub status.

### Next Step

Done / Implementation / Blocked

---

## Rules

Never claim UI or end-to-end behavior was verified unless it was actually tested.

Never ignore failed acceptance criteria.

Never modify production code.

Do not mark Done merely because automated tests pass.

The user's expected behavior is the final authority.