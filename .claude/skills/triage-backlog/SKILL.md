---
name: triage-backlog
description: Review GitLab Issues sitting in Inbox and move every non-blocked Issue into Backlog with a Priority set. Use when the user asks to move tasks that should be implemented into Backlog (e.g. "実装すべきタスクをBacklogに移動して").
model: haiku
---

# Triage Backlog

You are responsible for promoting Issues from `Inbox` to `Backlog`.

`Inbox` means: captured, not yet evaluated.

`Backlog` means: the project has decided this is worth doing eventually. It does not mean the Issue is implementable yet — that gate is `ready-issue` (`Backlog → Ready`).

This skill only decides *whether an Issue is blocked*, not whether it is fully specified. Every Inbox Issue that is not blocked moves to Backlog.

This is a **read-only stage**. It never writes to the repository — no production code, no
tests, no configuration, no documentation. Its only output is the `Inbox → Backlog` move
(with `Priority` set on each Issue it moves). See `CLAUDE.md` → **Read-Only Stages** for the
single definition; do not apply a different one here.

---

## Step 1: Collect Inbox Issues

List every Issue currently in:

`Inbox`

Then **exclude every Issue carrying the `epic` label**. An Epic is a tracking container for the
Issues underneath it, not a unit of work: it has no acceptance criteria that could be
implemented, so promoting it only consumes a `ready-issue` slot to produce `NOT READY`
(#925 got exactly that verdict on 2026-09-01). Epics stay in `Inbox` permanently, by the
user's decision (#1008).

Check the label on each Issue collected above:

```bash
glab issue view <number> -F json --jq '[.labels[]] | index("epic") != null'
```

`true` means skip it.

Excluded Epics are not assessed, not moved, and not classified. They are still **reported** in
Step 5 — silently dropping them would read as "Inbox is empty" when it is not.

Epics do not shield the Issues beneath them. A child of an Epic is an ordinary Inbox Issue and
is triaged normally.

If no Issues remain after the exclusion, report that — naming the Epics that were skipped — and
stop.

---

## Step 2: Evaluate each Issue

Delegate to the `project-planner` agent to independently assess each Inbox Issue.

Run the agent on the Haiku model — pass `model: "haiku"` to the Agent tool. This skill and every agent it spawns run on Haiku.

Assess each Issue for:

### Blocked

Is this Issue blocked? Judge this **per `CLAUDE.md` → Dependency Resolution** — that is the
single definition, shared with `ready-issue`, `work-next`, and `implement-issue`. Do not apply a
different one here.

At this gate that means: **no link and no board status blocks by itself** — GitLab CE has no
directional dependency link, so there is nothing here that can declare an Issue blocked. Backlog
does not assert the work is startable, only that it is worth doing, so a dependency that is
still open is not a reason to hold an Issue in Inbox. Whether the substance actually exists is
`ready-issue`'s gate, not
this one (#751).

Read the Issue's recorded dependencies with:

```bash
scripts/issue-dependency-status.sh <number>
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

The planner inherits this stage's read-only constraint (`CLAUDE.md` → **Read-Only Stages**):
it reads the Issue and the codebase, writes nothing, and must not decide
implementation-readiness here.

---

## Step 3: Classify each Issue

For every Inbox Issue, assign exactly one outcome:

### Move to Backlog

The default. Every Issue that is not blocked and is not a duplicate moves to Backlog, regardless of how thoroughly it is specified.

### Keep in Inbox

Blocked — reaching this classification requires a concrete, named reason why the work cannot be
started at all (not merely that a dependency Issue is open). Name it. An open dependency Issue is
not by itself a blocker here (see above).

Epics never reach this step: they are excluded in Step 1, before any classification.

### Recommend Closing

Duplicate of, or superseded by, another Issue. Do not close it yourself, and do not move it to Backlog — recommend it to the user with the Issue number it duplicates.

---

## Step 4: Apply the moves

Move every Issue classified `Move to Backlog` from `status::Inbox → status::Backlog`, and set
its priority label in the same call (see `CLAUDE.md` → **How to change status**):

```bash
glab api "projects/:id/issues/<iid>" --method PUT \
  -f "remove_labels=status::Inbox" -f "add_labels=status::Backlog,priority::P1"
```

Set the `Priority` field on every moved Issue. Never leave priority unset — `ready-issue` factors Priority into its selection (CLAUDE.md → Issue Provenance → Selection order; Priority is one key among several, not the first — `hotfix`, provenance and kind are read before it), and an unset priority still sinks the Issue toward the bottom within its own provenance/kind group.

Do not move `Keep in Inbox` or `Recommend Closing` Issues.

Do not close any Issue yourself — closing is the user's call.

Process every Inbox Issue in this run without pausing between items; report the full batch result at the end.

---

## Step 5: Report

Return one consolidated table, ordered by priority (highest first). Note whether each moved
Issue carries `hotfix` — it does not change anything `triage-backlog` itself does (Priority is
still set the same way), but it is the first thing `ready-issue` and `work-next` will look at
next (CLAUDE.md → Issue Provenance → Selection order):

## Triage Result

| Issue | Title | Priority | Hotfix | Outcome | Reason |
|---|---|---|---|---|---|

## Moved to Backlog

Count and list.

## Left in Inbox

Count and list, naming the concrete reason each one is held.

## Recommended for Closing

Count and list, with reason. Explicitly ask the user to confirm before closing.

## Skipped (Epic)

Count and list the `epic`-labelled Issues excluded in Step 1. These were not assessed and
remain in `Inbox` by design — say so, so the count is not read as an oversight.

## Next Step

Recommend running `ready-issue` to select the highest-priority Backlog Issue and promote it to Ready.

---

## Rules

Never write to the repository. Reviewing is the whole job: the only permitted mutations are
the ones listed for `triage-backlog` in `CLAUDE.md` → **Read-Only Stages** — move
`Inbox → Backlog` and set `Priority` on the Issues moved. Nothing else, in the repository or
on the board.

Never close an Issue without the user's explicit confirmation.

Never move an Issue straight from `Inbox` to `Ready` — it must pass through `Backlog` and then the `ready-issue` gate.

Do not hold a non-blocked Issue in Inbox because it looks under-specified, low-value, or hard to scope. Incomplete specification is `ready-issue`'s gate, not this one. The only reasons an Issue stays in Inbox are: it carries the `epic` label, it is blocked, or it is a duplicate recommended for closing.

Never move an `epic`-labelled Issue out of `Inbox` — not to `Backlog`, and not on the grounds that its children are done. Its board position is not a progress signal.

Run this skill, and every agent it spawns, on the Haiku model.
