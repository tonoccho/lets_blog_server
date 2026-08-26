---
name: ready-issue
description: Validate a GitHub Issue in Backlog and move it to Ready when its requirements, acceptance criteria, scope, and dependencies are sufficiently defined for implementation. Use when the user asks to make an Issue Ready for implementation.
---

# Ready Issue

You are responsible for promoting a GitHub Issue from `Backlog` to `Ready`.

`Ready` means:

> Claude Code is authorized to begin implementation without requiring additional product-level clarification.

This is a gate.

Do not move an Issue to Ready merely because the user requested it.

The Issue must pass the readiness checks.

---

# Input

The user may provide:

- An Issue number
- An Issue URL
- An Issue title
- A natural-language reference to an Issue

If no specific Issue is provided, ask the user which Issue should be evaluated.

Do not automatically select an arbitrary Backlog Issue.

---

# Step 1: Read the Issue

Retrieve the complete GitHub Issue.

Read:

- Title
- Description
- Acceptance Criteria
- Scope
- Out of Scope
- Dependencies
- Open Questions
- Labels
- Existing comments

Do not make a readiness decision from the Issue title alone.

---

# Step 2: Verify Issue status

Confirm that the Issue is currently:

`Backlog`

If it is already:

`Ready`

report that no change is necessary.

If it is:

`Inbox`

do not move it directly to Ready.

Run the planning workflow first.

If it is:

`In Progress`

`Review`

`QA`

or

`Done`

do not change its status.

Report the current state.

---

# Step 3: Invoke project-planner

Ask the `project-planner` agent to independently evaluate whether the Issue is implementation-ready.

The planner should verify:

1. Goal clarity
2. Requirement clarity
3. Acceptance criteria
4. Scope
5. Out-of-scope definition
6. Dependencies
7. Blocking questions
8. Existing architecture
9. Potential requirement conflicts

The planner must not modify production code.

---

# Step 4: Readiness Criteria

The Issue can become Ready only if all of the following are true.

## Goal

The desired outcome is clear.

PASS if:

A developer can explain what problem this Issue is intended to solve.

FAIL if:

The Issue describes only a technical action without explaining the desired outcome.

---

## Requirements

The expected behavior is sufficiently defined.

PASS if:

The implementer can determine what behavior is required.

FAIL if:

Important product behavior remains ambiguous.

---

## Acceptance Criteria

Acceptance criteria must be observable and testable.

Good:

- [ ] User can search articles by title.
- [ ] Empty search displays the default article list.
- [ ] No-match searches display the empty state.

Bad:

- [ ] Search should work well.
- [ ] UI should be intuitive.

---

## Scope

The Issue must have a reasonably bounded scope.

PASS if:

The implementer can determine what belongs to this Issue.

FAIL if:

The Issue could reasonably expand into a large collection of unrelated tasks.

---

## Out of Scope

Important exclusions should be documented.

This does not require every Issue to have a large Out of Scope section.

However, if reasonable interpretations could expand the scope, document those exclusions.

---

## Dependencies

Important dependencies must be known.

PASS if:

Dependencies are either:

- Resolved
- Explicitly accepted
- Not applicable

FAIL if:

Implementation depends on unfinished work that has not been identified.

---

## Blocking Questions

There must be no unresolved product-level blocking questions.

PASS:

`No blocking questions.`

FAIL:

Any question whose answer could materially change the implementation or user behavior.

---

## Architecture

The existing codebase must provide enough information for implementation.

The implementer does not need a complete technical design.

However, obvious architectural conflicts must be identified.

---

# Step 5: Produce Readiness Report

Before changing GitHub status, output:

## Readiness Report

### Issue

`#123 — Example title`

### Current Status

`Backlog`

### Checks

| Check | Result |
|---|---|
| Goal | PASS |
| Requirements | PASS |
| Acceptance Criteria | PASS |
| Scope | PASS |
| Out of Scope | PASS |
| Dependencies | PASS |
| Blocking Questions | PASS |
| Architecture | PASS |

### Decision

`READY`

or

`NOT READY`

### Reason

Explain the decision briefly.

### Required Changes

If NOT READY, list exactly what must be changed before the Issue can become Ready.

---

# Step 6: Handle NOT READY

If any blocking readiness criterion fails:

Do not change the GitHub Project status.

Keep:

`Backlog`

Report what needs to be clarified.

Example:

```text
Issue #123 is NOT READY.

Blocking problems:

1. It is unclear whether multiple tags can be assigned to one article.
2. It is unclear whether tags are user-created or administrator-created.

The Issue remains in Backlog.