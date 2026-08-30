---
name: triage-backlog
description: Review GitHub Issues sitting in Inbox and move every non-blocked Issue into Backlog with a Priority set. Use when the user asks to move tasks that should be implemented into Backlog (e.g. "実装すべきタスクをBacklogに移動して").
model: haiku
---

# Triage Backlog

You are responsible for promoting Issues from `Inbox` to `Backlog`.

`Inbox` means: captured, not yet evaluated.

`Backlog` means: the project has decided this is worth doing eventually. It does not mean the Issue is implementable yet — that gate is `ready-issue` (`Backlog → Ready`).

This skill only decides *whether an Issue is blocked*, not whether it is fully specified. Every Inbox Issue that is not blocked moves to Backlog.

Do not implement production code.

---

## Step 1: Collect Inbox Issues

List every Issue currently in:

`Inbox`

If there are none, report that and stop.

---

## Step 2: Evaluate each Issue

Delegate to the `project-planner` agent to independently assess each Inbox Issue.

Run the agent on the Haiku model — pass `model: "haiku"` to the Agent tool. This skill and every agent it spawns run on Haiku.

Assess each Issue for:

### Blocked

Is this Issue blocked? An Issue is blocked when it has an open `blocked_by` dependency, or its body documents a dependency on work that has not been done yet.

Read GitHub's issue dependencies with:

```bash
gh api repos/:owner/:repo/issues/<number>/dependencies/blocked_by
```

This is the only gate for staying in Inbox. "Not fully specified", "needs more detail", and "unclear acceptance criteria" are **not** blockers here — those are `ready-issue`'s gate, not this one.

### Priority

Use the project's `Priority` field (P0/P1/P2) if set. If unset, infer priority from:

1. Severity (a `bug` breaking existing behavior outranks a nice-to-have `enhancement`)
2. Whether other Backlog/Ready Issues depend on it
3. Whether it belongs to an in-flight Epic/tracking Issue and its position in that sequence
4. Age (older, still-relevant requests get a slight boost over brand-new ones, all else equal)

### Duplication

Is this Issue a duplicate of, or superseded by, another Issue?

The planner must not modify production code and must not decide implementation-readiness here.

---

## Step 3: Classify each Issue

For every Inbox Issue, assign exactly one outcome:

### Move to Backlog

The default. Every Issue that is not blocked and is not a duplicate moves to Backlog, regardless of how thoroughly it is specified.

### Keep in Inbox

Blocked — it has an open `blocked_by` dependency, or it depends on unfinished work. Name the blocker.

### Recommend Closing

Duplicate of, or superseded by, another Issue. Do not close it yourself, and do not move it to Backlog — recommend it to the user with the Issue number it duplicates.

---

## Step 4: Apply the moves

Move every Issue classified `Move to Backlog` from `Inbox → Backlog`.

Set the `Priority` field on every moved Issue. Never leave priority unset — `ready-issue` selects by Priority first, so an unset priority sinks the Issue to the bottom of the selection order.

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

Count and list, naming the specific blocker (the open `blocked_by` Issue, or the unfinished work it depends on) for each one.

## Recommended for Closing

Count and list, with reason. Explicitly ask the user to confirm before closing.

## Next Step

Recommend running `ready-issue` to select the highest-priority Backlog Issue and promote it to Ready.

---

## Rules

Never implement or modify production code.

Never close an Issue without the user's explicit confirmation.

Never move an Issue straight from `Inbox` to `Ready` — it must pass through `Backlog` and then the `ready-issue` gate.

Do not hold a non-blocked Issue in Inbox because it looks under-specified, low-value, or hard to scope. Incomplete specification is `ready-issue`'s gate, not this one. The only reasons an Issue stays in Inbox are: it is blocked, or it is a duplicate recommended for closing.

Run this skill, and every agent it spawns, on the Haiku model.
