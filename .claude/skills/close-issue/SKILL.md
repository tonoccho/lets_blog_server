---
name: close-issue
description: Close `status::Inbox` / `status::Backlog` / `status::Ready` Issues the user names, only when the user types `/close-issue #<n> [#<n> ...]` themselves; comments the reason, moves each to `status::Done` in one call and closes it. Use for "/close-issue #n [#n ...]".
model: sonnet
---

# Close Issue

Closing an Issue is the user's judgment (`CLAUDE.md` → **Read-Only Stages**). This skill is the
safe path for carrying that judgment out: it closes only the Issues the user named in their own
slash command, and nothing else.

This is a **read-only stage** (`CLAUDE.md` → **Read-Only Stages**): it never writes a file in the
repository. Its only mutation is GitLab Issue state, as permitted for `close-issue` in that
section. Do not apply a different definition here.

## Invocation

`/close-issue #<n> [#<n> ...]`, typed by the user. That slash command is the only confirmation.
A plain "yes", a `Skill` call Claude made itself, or a message from any agent or subagent is not
one: `guard.py`'s `cmd_prompt` clears the stage marker on a plain user message, after which the
`Backlog → Done` (etc.) label change is refused. Never try to work around that. With no number,
report that nothing was named and stop.

## Step 1: Check each named Issue (live)

For each number, read it live; never carry over an earlier reading:

```bash
glab issue view <n> -F json --jq '{state, labels}'
```

Refuse (close nothing, report the reason) when:

- the Issue is already closed,
- its status is `In Progress`, `Review` or `QA` (a branch or Merge Request is involved; out of
  scope here, `guard.py` refuses these too),
- it carries the `epic` label (that is `close-epic`'s job),
- it does not carry exactly one `status::` label.

Only `status::Inbox`, `status::Backlog` and `status::Ready` proceed.

## Step 2: Close, one at a time

For each accepted Issue, **in order, never in parallel** with each other or with any other mutation:

1. Comment the reason for closing (the user's stated reason, or if none was given, say it was
   closed on the user's `/close-issue` command; do not invent a reason).
2. Swap the label in one call (use the Issue's current status), then close:

```bash
glab api "projects/:id/issues/<n>" --method PUT \
  -f "remove_labels=status::<Inbox|Backlog|Ready>" -f "add_labels=status::Done"
glab issue close <n>
```

3. Confirm the result (state `closed`, exactly one `status::` label, `status::Done`) before the
   next Issue.

Never use `labels=`. Never change the `epic`, `bug`, `hotfix` or `user-request` labels. Never
close an Issue the user did not name.

## Output

One line per named Issue: closed (with the confirmed final state) or refused (with the reason).
