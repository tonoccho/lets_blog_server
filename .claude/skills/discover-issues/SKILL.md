---
name: discover-issues
description: Proactively review the entire repository (architecture, code quality, tech debt, missing tests, TODOs, inconsistencies) and register each distinct finding as a new GitLab Issue in Inbox. Use this when the user asks Claude to look over the whole codebase/repo and file issues from what it finds, rather than converting one specific request.
model: opus
---

# Discover Issues

You are running a repository-wide audit to surface work that nobody has explicitly requested yet.

This is one of the three entry points into the Issue registration workflow:

1. Full-repository review (this skill)
2. A specific feature request → `plan-issue`
3. A specific bug report → `plan-issue`

This is a **read-only stage**. It never writes to the repository — no production code, no
tests, no configuration, no documentation. Its only output is GitLab Issues. See
`CLAUDE.md` → **Read-Only Stages** for the single definition of what that forbids and which
GitLab mutations it permits; do not apply a different one here.

---

## Step 1: Scope the audit

Determine the audit scope from the user's request.

If the user named an area (a service, a directory, "the web frontend", "the auth flow"):

Limit the audit to that area.

If the user asked for a full repository review with no area named:

Cover the repository broadly, but do not attempt to inspect every file exhaustively in one pass. Prioritize:

- Architecture docs (`docs/`, ADRs) vs. actual code — do they still agree?
- `TODO` / `FIXME` / `XXX` comments
- Inconsistent patterns between similar services/modules
- Missing or clearly inadequate test coverage for critical paths
- Obvious correctness or security concerns noticed while reading (not a full security audit)
- Stale or misleading documentation
- Known-broken behavior mentioned in docs, comments, or existing (closed) Issues but never fixed

Report which areas were actually covered versus skipped due to scope, so the user knows this is a sample, not exhaustive coverage.

---

## Step 2: Check for existing coverage

Before treating anything as a new finding:

1. Search open Issues (`glab issue list --search "<term>"`, all statuses) for related titles/keywords.
2. Search closed Issues for prior decisions (e.g. `wontfix`) that would make re-filing pointless.
3. Check whether the finding is already tracked inside an existing Epic/tracking Issue.

Do not create a duplicate Issue. If a related Issue exists, note it instead (e.g., as a comment or as a Dependency on the new Issue) rather than re-filing.

---

## Step 3: Investigate each candidate finding

Delegate to the `project-planner` agent, once per candidate finding (or batched if closely
related). The planner inherits this stage's read-only constraint — it reads the code to
confirm a finding, and never fixes what it finds:

1. Confirm the finding is real (read the actual code, not just a comment claiming a problem).
2. Determine impact and who is affected.
3. Draft a proposed Issue using the same template as `plan-issue` Step 4 (Title, Background, Problem, Goal, Requirements, Acceptance Criteria, Scope, Out of Scope, Dependencies, Open Questions, Implementation Notes). **At most five Acceptance Criteria** — see `CLAUDE.md` → **At most five Acceptance Criteria**; a finding that needs more is more than one Issue.
4. Assess a Priority (P0/P1/P2) and Size (XS–XL) using the project's existing Priority/Size fields. Priority is mandatory for every Issue this skill files, even though many pre-existing Issues in the project leave it unset.

Do not invent problems to hit a quota. A short list of real findings is better than a long list of speculative ones.

---

## Step 4: Filter low-value findings

Discard candidates that are:

- Purely stylistic with no functional or maintainability impact
- Already effectively covered by an existing open Issue
- Too vague to act on without inventing requirements

Cap the batch at a reasonable number for one run (roughly 10 or fewer). If more real findings exist, report the rest as a short list without filing them, and let the user ask for another pass.

---

## Step 5: Create the Issues

For each surviving finding:

Create a GitLab Issue using the `plan-issue` template and conventions.

Set status:

`Inbox`

Do not set status to `Backlog` or `Ready` — discovered work has not been triaged yet.

Set the `Priority` field (P0/P1/P2) determined in Step 3. Never leave a newly created Issue with priority unset.

Apply appropriate existing labels (e.g. `bug`, `enhancement`, `architecture`) where they clearly fit.

Never apply `user-request`. Everything this skill files is Claude's own finding — that stays
true when the user is the one who ran the sweep. See `CLAUDE.md` → **Issue Provenance**.

---

## Step 6: Report

Return:

## Audit Scope

What was actually reviewed, and what was skipped.

## Issues Created

For each: Issue number, title, one-line reason it matters.

## Related Existing Issues

Findings that overlap with Issues already open — not re-filed.

## Discarded Candidates

Findings considered but not filed, with a one-line reason.

## Next Step

Recommend running `triage-backlog` to decide which of the newly created Inbox Issues are worth committing to.

---

## Rules

Never write to the repository during this workflow. Reviewing is the whole job: the only
permitted mutations are the ones listed for `discover-issues` in `CLAUDE.md` →
**Read-Only Stages** (create Issues in `Inbox` with `Priority` set; comment on an existing
Issue that already covers a finding). A fix that looks like a one-liner is still a code
change — file it instead.

Never file an Issue for a finding that was not actually verified against the current code.

Never silently skip Step 2 (duplicate check) — a flood of duplicate Issues is worse than a smaller, accurate list.

Always create in `Inbox`, never directly in `Backlog` or `Ready`.
