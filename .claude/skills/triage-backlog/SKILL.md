---
name: triage-backlog
description: Review GitHub Issues sitting in Inbox and move the ones worth committing to into Backlog, ordered by priority. Use when the user asks to move tasks that should be implemented into Backlog (e.g. "実装すべきタスクをBacklogに移動して").
---

# Triage Backlog

You are responsible for promoting Issues from `Inbox` to `Backlog`.

`Inbox` means: captured, not yet evaluated.

`Backlog` means: the project has decided this is worth doing eventually. It does not mean the Issue is implementable yet — that gate is `ready-issue` (`Backlog → Ready`).

This skill only decides *whether an Issue is worth committing to*, not whether it is fully specified.

Do not implement production code.

---

## Step 1: Collect Inbox Issues

List every Issue currently in:

`Inbox`

If there are none, report that and stop.

---

## Step 2: Evaluate each Issue

Delegate to the `project-planner` agent to independently assess each Inbox Issue for:

### Value

Does this solve a real problem? Is it duplicative of, or superseded by, another Issue?

### Priority

Use the project's `Priority` field (P0/P1/P2) if set. If unset, infer priority from:

1. Severity (a `bug` breaking existing behavior outranks a nice-to-have `enhancement`)
2. Whether other Backlog/Ready Issues depend on it
3. Whether it belongs to an in-flight Epic/tracking Issue and its position in that sequence
4. Age (older, still-relevant requests get a slight boost over brand-new ones, all else equal)

### Committability

Is this something the project actually intends to do, as opposed to something to leave open for future consideration or close as `wontfix`/`duplicate`?

The planner must not modify production code and must not decide implementation-readiness here — only whether the work is worth committing to.

---

## Step 3: Classify each Issue

For every Inbox Issue, assign exactly one outcome:

### Move to Backlog

The project intends to do this. Priority is at least roughly understood.

### Keep in Inbox

Still undecided — needs more information or a product decision before it's worth committing to. Explain what's missing.

### Recommend Closing

Duplicate, superseded, or out of scope for the project. Do not close it yourself — recommend it to the user with a reason.

---

## Step 4: Apply the moves

Move every Issue classified `Move to Backlog` from `Inbox → Backlog`.

Set the `Priority` field when a reasonably confident priority was determined.

Do not move `Keep in Inbox` or `Recommend Closing` Issues.

Do not close any Issue yourself — closing is the user's call.

Process every Inbox Issue in this run without pausing between items; report the full batch result at the end.

---

## Step 5: Report

Return one consolidated table, ordered by priority (highest first):

## Triage Result

| Issue | Title | Priority | Outcome | Reason |
|---|---|---|---|---|

## Moved to Backlog

Count and list.

## Left in Inbox

Count and list, with what's blocking each one — point unresolved product questions at `plan-issue` for clarification.

## Recommended for Closing

Count and list, with reason. Explicitly ask the user to confirm before closing.

## Next Step

Recommend running `ready-issue` (batch mode) to find which Backlog Issues are now implementable.

---

## Rules

Never implement or modify production code.

Never close an Issue without the user's explicit confirmation.

Never move an Issue straight from `Inbox` to `Ready` — it must pass through `Backlog` and then the `ready-issue` gate.

Do not treat "worth doing" as the same judgment as "ready to implement" — those are two separate gates in this project's pipeline.
