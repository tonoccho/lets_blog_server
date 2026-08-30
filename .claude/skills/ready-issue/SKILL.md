---
name: ready-issue
description: Validate GitHub Issue(s) in Backlog and move them to Ready when requirements, acceptance criteria, scope, and dependencies are sufficiently defined for implementation. Supports a single named Issue, or selecting the single highest-priority Backlog Issue (Priority → Is blocking count → oldest Issue number) and promoting it (e.g. "実装可能なタスクをReadyに移動して"). Use when the user asks to make Issue(s) Ready for implementation.
model: haiku
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

Otherwise — the user asks broadly to move implementable work into Ready without naming one Issue (e.g. "実装可能なタスクをReadyに移動して", "promote what's ready", "次にやるべきタスクをReadyにして"), or gives no target at all:

Use **Select-Next Mode**.

Select-Next Mode promotes **one** Issue per run — the single highest-priority Backlog Issue. It is not a batch sweep. Never pick an arbitrary Backlog Issue: the selection order in Select-Next Mode is fixed and must be followed.

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

Run the agent on the Sonnet model — pass `model: "sonnet"` to the Agent tool. The skill body itself runs on Haiku (selection is a property comparison), but judging an Issue's readiness means actually reading and assessing it, so the evaluation is delegated at Sonnet.

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

# Select-Next Mode

This mode promotes exactly **one** Issue per run: the single highest-priority Backlog Issue that passes the Readiness Criteria.

## Step 1: Collect Backlog Issues

List every Issue currently in `Backlog`.

If there are none, report that and stop.

## Step 2: Exclude blocked Issues

Drop from the candidate list any Issue that is blocked — it has an open `blocked_by` dependency, or its body documents an unresolved dependency on unfinished work.

Read GitHub's issue dependencies with:

```bash
gh api repos/:owner/:repo/issues/<number>/dependencies/blocked_by
gh api repos/:owner/:repo/issues/<number>/dependencies/blocking
```

A blocked Issue can never be `READY` — its `Dependencies` check fails by definition.

If every Backlog Issue is blocked, report that and stop.

## Step 3: Rank the candidates

Sort the remaining candidates by this fixed order:

1. **Priority — highest first.** `P0` > `P1` > `P2` > unset. Unset always ranks last.
2. **Is blocking count — largest first.** The number of open Issues this Issue blocks, from `dependencies/blocking`. An Issue that unblocks more work is selected first.
3. **Issue number — oldest first.** The lowest Issue number wins.

These three keys are applied strictly in order. Do not substitute your own judgment about which Issue is more interesting or easier.

## Step 4: Evaluate the top candidate

Run Steps 1–4 of **Single-Issue Mode** on the highest-ranked candidate.

If it evaluates `READY`, move `Backlog → Ready` and stop — one Issue per run.

If it evaluates `NOT READY`, leave it in `Backlog`, record the specific blocking reason, and move on to the next candidate in rank order without pausing to ask whether to continue.

Stop after at most **5** candidates have been evaluated. If none of them was `READY`, report that and stop — the Backlog needs `plan-issue` work before anything can be promoted.

## Step 5: Report

## Selection Result

### Selected

`#123 — Example title`

| Key | Value |
|---|---|
| Priority | P1 |
| Is blocking | 3 open Issues (#130, #131, #145) |
| Issue number | 123 |
| Decision | READY |

Include the Readiness Report table for the selected Issue.

### Skipped

Every candidate evaluated ahead of the selected one, each with the specific readiness criterion that failed and what must change. Leave these in `Backlog`.

### Next Step

Recommend running `work-next` (or "次のタスクを実装して") to start implementing the Issue just moved to Ready.

---

# Rules

Never modify production code.

Never move an Issue directly from `Inbox` to `Ready`.

Never move an Issue to `Ready` when a blocking readiness criterion fails.

In Select-Next Mode, promote at most one Issue per run — it is a selection gate, not a batch sweep.

Do not stop the selection just because the top candidate is `NOT READY` — continue down the ranked list (up to the 5-candidate cap) and report every skipped Issue with its reason.

Run this skill on the Haiku model, and delegate the readiness evaluation to `project-planner` on the Sonnet model.
