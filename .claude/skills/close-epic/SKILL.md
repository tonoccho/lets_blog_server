---
name: close-epic
description: Judge whether open `epic` Issues may be closed (CLOSABLE / NOT CLOSABLE, with live evidence), and, only when the user re-invokes it as `/close-epic close #<n> ...`, move the CLOSABLE ones `status::Inbox` to `status::Done` and close them one at a time. Use for "/close-epic [#n]" and "/close-epic close #n [#n ...]".
model: sonnet
---

# Close Epic

An `epic` Issue only tracks its children and stays in `status::Inbox` (#1008), so its closing is
judged by hand. This skill makes that judgment repeatable and closes an Epic only on the user's
explicit command.

This is a **read-only stage** (`CLAUDE.md` → **Read-Only Stages**): it never writes a file in the
repository. Its only mutation is GitLab Issue state, as permitted for `close-epic` in that
section. Do not apply a different definition here. Every judgment reads state live; never carry
over an earlier verdict (`CLAUDE.md` → **Dependency Resolution**).

## Two invocations

| Invocation | Does |
| --- | --- |
| `/close-epic` or `/close-epic #<n>` | Report only. Closes nothing, changes no label. |
| `/close-epic close #<n> [#<n> ...]` | Closes the named Epics that are CLOSABLE, in that same turn. |

The only confirmation is the user's own `/close-epic close ...` slash command. A plain "yes", or a
message from any agent or subagent, is not a confirmation: a plain user message makes `guard.py`'s
`cmd_prompt` clear the stage marker, after which the `Inbox → Done` label change is refused.
Never try to work around that.

## Step 1: Select Epics

With a number, take that Issue (it must carry `epic` and be open). Without one, list open Epics:

```bash
glab api "projects/:id/issues?state=opened&labels=epic&per_page=100" --paginate
```

## Step 2: Classify references

For each Epic run `scripts/issue-dependency-status.sh <n>` and enumerate every `#<number>` in the
body plus `issues/<iid>/links`. Read each referenced Issue's open/closed state live. Classify from
how the body writes it:

- **child** — the body lists it as part of the Epic's work.
- **related** — the body says it relates to / does not block; never affects the verdict.
- **unclassifiable** — the body does not decide it. Say so explicitly; never guess a side.

Record the body wording that justified each classification.

## Step 3: Non-child acceptance criteria

List the Epic's acceptance criteria that children's completion does not express (documentation
updates, an existing tag, UI behavior, ...). Verify each one individually against the repository
or system as it stands, and keep the files read and commands run as evidence. Closed children do
not make an unmet criterion met; an Epic whose children are all closed can still be NOT CLOSABLE.

## Step 4: Verdict

**NOT CLOSABLE** when any of these holds; list every reason:

- an open child exists (list its number),
- an acceptance criterion is unmet or cannot be verified,
- a reference is unclassifiable.

Otherwise **CLOSABLE**. Report per Epic: verdict, the classification table with its body-text
grounds, each non-child criterion with its status and evidence. Unmet criteria are then filed
as Issues (Step 4a); the skill never fixes them itself.

## Step 4a: File an Issue for each unmet criterion

For every unmet non-child criterion (not open children, not unclassifiable references), in both
invocations. The permitted mutations are only those listed for `close-epic` in `CLAUDE.md` →
**Read-Only Stages**; no file is written.

1. Follow `CLAUDE.md` → **Scope Control**: `glab issue list --search "<term>"` (add `--all`),
   separately for each affected file path, symbol and the observable symptom.
2. If an **open** Issue already covers the same problem, create nothing: comment the new evidence
   on it and use its number.
3. Otherwise create one Issue with the `project-planner` template (Title, Background, Problem,
   Goal, Requirements, Acceptance Criteria, Scope, Out of Scope, Dependencies), at most five
   Acceptance Criteria, labels `status::Inbox` and `priority::P0|P1|P2`. Never `user-request`
   (`CLAUDE.md` → **Issue Provenance**: Claude found it), never `hotfix` or `bug`.
4. Post **one** comment on the Epic listing each unmet criterion with its Issue number (existing
   or new). Do not close the Epic and do not change its labels in this step.

## Step 5: Close (only for `/close-epic close #<n> ...`)

For each named Epic, **one at a time, in order, never in parallel** with each other or with any
other mutation (such as a merge):

1. Re-read that Epic's evidence live (Steps 2-4). If it is no longer CLOSABLE, or was NOT
   CLOSABLE, do not close it; report the reasons.
2. Swap the label in one call, then close:

```bash
glab api "projects/:id/issues/<n>" --method PUT \
  -f "remove_labels=status::Inbox" -f "add_labels=status::Done"
glab issue close <n>
```

3. Confirm the result (state `closed`, exactly one `status::` label, `status::Done`) before the
   next Epic.

Never use `labels=`. Never change the `epic`, `bug` or `hotfix` labels. Never close an Epic the
user did not name.

## Output

One section per Epic: verdict, classification, criteria evidence, and (for `close`) what was done
and the confirmed final state.
