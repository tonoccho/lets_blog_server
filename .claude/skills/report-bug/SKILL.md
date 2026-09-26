---
name: report-bug
description: Report an urgent bug you just hit yourself, in one short sentence, and file it directly as a hotfix Issue in status::Backlog — skipping Inbox and triage-backlog. Use for "/report-bug <short description>" when the bug is urgent enough to jump the queue ahead of the ordinary backlog. Not for routine bugs or feature requests; use plan-issue for those.
model: opus
---

# Report Bug

You are the entry point for an urgent, user-hit bug report.

`/report-bug <short description>` investigates the codebase enough to file one complete GitLab
Issue, and puts it directly into `status::Backlog` with `hotfix` — skipping `status::Inbox` and
`triage-backlog` (`CLAUDE.md` → **How to change status** → the sole exception to every other
Issue starting in `Inbox`).

This is a **read-only stage**. It never writes to the repository — no production code, no
tests, no configuration, no documentation. Its only output is one GitLab Issue. See
`CLAUDE.md` → **Read-Only Stages** for the single definition of what that forbids and which
GitLab mutation it permits here; do not apply a different one here.

---

## Step 1: Check the hotfix cap before investigating

`hotfix` allows **at most 3 open Issues at once** (`CLAUDE.md` → **Issue Provenance** →
**hotfix**). Check this first, so investigation is never wasted work that cannot be filed.

```bash
glab api "projects/:id/issues?state=opened&labels=hotfix" --paginate
```

`glab api` does not support `--jq`; count the entries yourself from the returned pages.

If 3 or more open Issues already carry `hotfix`:

**Stop. Do not create an Issue.** Report the existing 3 Issues' numbers and titles, and offer
the user exactly two choices:

1. Remove `hotfix` from one of the existing 3 via the GitLab web UI, then re-run `/report-bug`.
2. File this report without `hotfix` (`user-request`, `bug`, `priority::P0`, `status::Backlog`
   only) so it still enters the Backlog, just without the queue-jump.

**Never remove an existing Issue's `hotfix` yourself** — `CLAUDE.md` → **Issue Provenance** →
**hotfix**: only the user adds or removes it, exactly like `bug`, and `guard.py` refuses the
attempt from Claude regardless.

If fewer than 3 are open, continue to Step 2.

---

## Step 2: Investigate and draft the Issue

Delegate to the `project-planner` agent, using the same Issue template and investigation
approach as `plan-issue` Step 2 and Step 4 (Title, Background, Problem, Goal, Requirements,
Acceptance Criteria, Scope, Out of Scope, Dependencies, Open Questions, Implementation Notes).
Do not redefine that template here — see `.claude/skills/plan-issue/SKILL.md` and
`.claude/agents/project-planner.md` for it.

The one thing specific to this entry point: the user's short bug description is only the seed.
The planner investigates the actual code (not just the description given) to fill in, inside
the ordinary Background / Problem sections:

- The phenomenon as the user observed it.
- Steps to reproduce.
- Expected behavior vs. actual behavior.

**At most five Acceptance Criteria** — see `CLAUDE.md` → **At most five Acceptance Criteria**.
A bug urgent enough to jump the queue is still bound by the same cap; if it genuinely needs
more, split it and file the follow-up the normal way (`plan-issue`), noting it under Out of
Scope.

If investigation surfaces a genuine blocking ambiguity, stop and ask the user only what is
necessary — do not guess at reproduction steps or expected behavior.

---

## Step 3: File the Issue

Create the Issue with exactly this fixed label set and status:

```bash
glab issue create --title "<short title>" --label "user-request,bug,priority::P0,hotfix,status::Backlog" --description "<the drafted Issue body>"
```

- `user-request` — the user reported this themselves (`CLAUDE.md` → **Issue Provenance**).
- `bug` — always; `/report-bug` exists for bugs, not feature requests.
- `priority::P0` — always; urgent enough to jump the queue is `P0` by definition.
- `hotfix` — this create-time grant is allowed only from this skill. `guard.py`'s
  hotfix-creation gate denies `hotfix` on `glab issue create` from any other caller
  (`CLAUDE.md` → **Issue Provenance** → **hotfix**).
- `status::Backlog` — the one exception to every other Issue starting in `Inbox`
  (`CLAUDE.md` → **How to change status**). This is Issue creation, not a `status::`
  transition, so the Legal Transitions table is unaffected.

If Step 1 found the cap full and the user chose to file without `hotfix`, drop `hotfix` from
the label list above and keep the rest (`user-request,bug,priority::P0,status::Backlog`).

---

## Step 4: Report

Return:

## Issue

The Issue number and title.

## Labels

The exact label set applied.

## Readiness Signal

One of:

- Ready candidate
- Needs clarification

Use the same criteria as `plan-issue` Step 6 — this is informational only. Do not decide
`Backlog → Ready` yourself; that is `ready-issue`'s job.

## Next Step

Recommend running `ready-issue` on the new Issue to judge its readiness for implementation.

---

## Rules

Never write to the repository. This is a **read-only stage** (`CLAUDE.md` → **Read-Only
Stages**): the only permitted mutation is creating exactly one Issue with the label set above.

Never create the Issue when 3 open Issues already carry `hotfix` — stop and offer the two
choices in Step 1 instead.

Never add or remove `hotfix` on any *existing* Issue — that is the user's exclusively
(`CLAUDE.md` → **Issue Provenance** → **hotfix**), and `guard.py` refuses the attempt regardless.

Never move the created Issue past `status::Backlog` — `Backlog → Ready` is `ready-issue`'s job.

---

## Related skills

- `plan-issue` — the ordinary entry point for a feature request or a non-urgent bug report;
  supplies the Issue template and Issue creation conventions this skill reuses.
- `ready-issue` — judges the Issue this skill files for promotion to `Ready`.
