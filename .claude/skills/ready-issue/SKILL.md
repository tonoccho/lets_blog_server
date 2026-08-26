---
name: ready-issue
description: Validate GitHub Issue(s) in Backlog and move them to Ready when requirements, acceptance criteria, scope, and dependencies are sufficiently defined for implementation. Supports a single named Issue or a batch sweep of all Backlog Issues by priority (e.g. "実装可能なタスクをReadyに移動して"). Use when the user asks to make Issue(s) Ready for implementation.
---

# Ready Issue

You are responsible for promoting GitHub Issues from `Backlog` to `Ready`.

`Ready` means:

> Claude Code is authorized to begin implementation without requiring additional product-level clarification.

This is a gate.

Do not move an Issue to Ready merely because the user requested it.

The Issue must pass the readiness checks below.

---

# Choose a mode

If the user names a specific Issue (number, URL, title, or clear natural-language reference):

Use **Single-Issue Mode**.

If the user asks broadly to move implementable work into Ready without naming one Issue (e.g. "実装可能なタスクをReadyに移動して", "promote what's ready", "sweep the backlog"):

Use **Batch Mode**.

If neither applies — no Issue named and no batch request implied — ask the user which Issue to evaluate, or whether to run a batch sweep. Do not silently pick an arbitrary Backlog Issue.

---

# Single-Issue Mode

## Step 1: Read the Issue

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

## Step 2: Verify Issue status

Confirm that the Issue is currently:

`Backlog`

If it is already `Ready`, report that no change is necessary.

If it is `Inbox`, do not move it directly to Ready — it must go through `triage-backlog` first.

If it is `In Progress`, `Review`, `QA`, or `Done`, do not change its status. Report the current state.

---

## Step 3: Invoke project-planner

Ask the `project-planner` agent to independently evaluate whether the Issue is implementation-ready, per the Readiness Criteria below.

The planner must not modify production code.

---

## Step 4: Apply the Readiness Criteria

The Issue can become Ready only if all of the following are true.

### Goal

The desired outcome is clear.

PASS if a developer can explain what problem this Issue is intended to solve.

FAIL if the Issue describes only a technical action without explaining the desired outcome.

### Requirements

The expected behavior is sufficiently defined.

PASS if the implementer can determine what behavior is required.

FAIL if important product behavior remains ambiguous.

### Acceptance Criteria

Acceptance criteria must be observable and testable.

Good:
- [ ] User can search articles by title.
- [ ] Empty search displays the default article list.
- [ ] No-match searches display the empty state.

Bad:
- [ ] Search should work well.
- [ ] UI should be intuitive.

### Scope

The Issue must have a reasonably bounded scope.

PASS if the implementer can determine what belongs to this Issue.

FAIL if the Issue could reasonably expand into a large collection of unrelated tasks.

### Out of Scope

Important exclusions should be documented if reasonable interpretations could otherwise expand the scope.

### Dependencies

PASS if dependencies are resolved, explicitly accepted, or not applicable.

FAIL if implementation depends on unfinished work that has not been identified.

### Blocking Questions

PASS: no blocking questions remain.

FAIL: any open question whose answer could materially change the implementation or user behavior.

### Architecture

The existing codebase must provide enough information for implementation. The implementer does not need a complete technical design, but obvious architectural conflicts must be identified.

---

## Step 5: Produce the Readiness Report

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

`READY` or `NOT READY`

### Reason

Explain the decision briefly.

### Required Changes

If NOT READY, list exactly what must change before the Issue can become Ready.

---

## Step 6: Handle the decision

If `NOT READY`:

Do not change the GitHub Project status. Keep `Backlog`. Report what needs to be clarified, e.g.:

```text
Issue #123 is NOT READY.

Blocking problems:

1. It is unclear whether multiple tags can be assigned to one article.
2. It is unclear whether tags are user-created or administrator-created.

The Issue remains in Backlog.
```

If `READY`:

Change:

`Backlog → Ready`

Confirm the status change and report the new state.

---

# Batch Mode

## Step 1: Collect Backlog Issues

List every Issue currently in `Backlog`.

If there are none, report that and stop.

## Step 2: Order by priority

Order the list using:

1. The `Priority` field (P0/P1/P2) if set.
2. Blocking dependencies (an Issue that unblocks others is evaluated before the Issues it blocks).
3. Position within an in-flight Epic/tracking Issue's sequence, if applicable.
4. Issue age (older first), as a tiebreaker.

## Step 3: Evaluate every Issue

Run Steps 1–4 of **Single-Issue Mode** for each Backlog Issue in priority order, without pausing between Issues to ask whether to continue.

## Step 4: Apply moves

For every Issue evaluated `READY`, change `Backlog → Ready`.

For every Issue evaluated `NOT READY`, leave it in `Backlog`.

## Step 5: Report

Return one consolidated table instead of per-Issue reports, ordered by priority:

## Backlog Sweep Result

| Issue | Title | Priority | Decision | Reason |
|---|---|---|---|---|

### Moved to Ready

Count and list.

### Remaining in Backlog

Count and list, each with the specific blocking reason from its Readiness Report.

### Next Step

Recommend running `work-next` (or "次のタスクを実装して") to start implementing the highest-priority Ready Issue.

---

# Rules

Never modify production code.

Never move an Issue directly from `Inbox` to `Ready`.

Never move an Issue to `Ready` when a blocking readiness criterion fails, even under batch mode time pressure.

In Batch Mode, do not stop the sweep just because one Issue is `NOT READY` — continue evaluating the rest and report all results together.
