---
name: plan-issue
description: Convert a user request, feature idea, bug report, or improvement request into a clear GitHub Issue. Use this skill as the entry point for development work.
model: opus
---

# Plan Issue

You are starting the project planning workflow.

Your job is to transform an informal request into an implementation-ready GitHub Issue.

Do not implement production code.

Delegate requirement analysis to the `project-planner` agent.

---

## Workflow

### Step 1: Understand the user's request

First determine what the user is asking for.

The request may be:

- A new feature
- A bug report
- A UI improvement
- A refactoring request
- A performance problem
- A technical improvement
- A vague product idea

Do not assume that every request should immediately become one Issue.

---

### Step 2: Invoke project-planner

Ask the `project-planner` agent to:

1. Understand the request.
2. Inspect the relevant codebase.
3. Identify existing related functionality.
4. Identify missing requirements.
5. Identify dependencies.
6. Determine whether the request should be one Issue or multiple Issues.
7. Produce a proposed Issue.

The planner must not modify production code.

---

### Step 3: Resolve blocking questions

If the planner identifies blocking questions:

Stop the workflow.

Ask the user only the necessary questions.

Do not create a Ready Issue while blocking questions remain unresolved.

The Issue remains in Inbox.

---

### Step 4: Create the GitHub Issue

Once the requirements are sufficiently clear:

Create a GitHub Issue containing:

- Title
- Background
- Problem
- Goal
- Requirements
- Acceptance Criteria
- Scope
- Out of Scope
- Dependencies
- Open Questions
- Implementation Notes

Use the repository's existing Issue conventions if they exist.

---

### Step 5: Set initial status

The default status after creation is:

`Inbox`

Every newly created Issue starts in `Inbox`, no matter how well-defined it already looks.

Promotion out of `Inbox` is intentionally a separate, later step, not part of registration:

- `Inbox → Backlog` is decided by the `triage-backlog` skill.
- `Backlog → Ready` is decided by the `ready-issue` skill.

Do not automatically move a newly created Issue to `Backlog` or `Ready` unless:

- The user explicitly requested that specific target status for this Issue in the same request, or
- The project workflow explicitly allows the planner to skip a stage.

---

### Step 6: Readiness signal (informational only)

Even though the Issue stays in `Inbox`, record a readiness signal to help the later triage/ready steps prioritize:

- [ ] Goal is clear
- [ ] Requirements are understandable
- [ ] Acceptance criteria are testable
- [ ] Scope is bounded
- [ ] Out-of-scope behavior is clear
- [ ] Blocking dependencies are resolved
- [ ] No blocking questions remain

If all conditions are satisfied, report:

`Ready candidate`

Otherwise report:

`Needs clarification`

This signal does not change the Issue's actual GitHub status.

---

## Output

Return:

### Issue

The Issue number and title.

### Status

`Inbox`

### Readiness Signal

One of:

- Ready candidate
- Needs clarification

### Summary

A concise explanation of the requested work.

### Blocking Questions

List unresolved questions.

If none:

`None`

### Next Step

Explain what should happen next.

---

## Rules

Never implement code during this workflow.

Never invent requirements.

Never silently resolve important product decisions.

Prefer small, independently deliverable Issues.

If the request is too large, propose splitting it.

The GitHub Issue is the source of truth for the implementation.

---

## Related skills

- `discover-issues` — reuses this skill's Issue template when registering multiple Issues found from a full-repository review.
- `triage-backlog` — moves Issues this skill created from `Inbox` to `Backlog`.
- `ready-issue` — moves Issues from `Backlog` to `Ready`.