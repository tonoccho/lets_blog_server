---
name: review-issue
description: Independently review an implementation against its GitHub Issue, acceptance criteria, architecture, code quality, and regression risks. Use this skill when an Issue is in Review.
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

- GitHub Issue
- Acceptance Criteria
- Implementation Plan
- Git diff
- Changed files
- Relevant architecture documentation
- Relevant tests

Do not review based only on the implementation summary.

---

## Step 3: Invoke reviewer

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

## Step 5: Update GitHub status

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

Explain the GitHub status transition.

### Next Step

State exactly what should happen next.

---

## Rules

Do not modify production code.

Do not approve without comparing against the original Issue.

Do not treat stylistic preferences as blocking issues.

Do not invent problems.

The reviewer must remain independent from the implementation process.