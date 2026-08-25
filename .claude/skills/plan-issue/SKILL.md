---
name: plan-issue
description: Convert a user request, feature idea, bug report, or improvement request into a clear GitHub Issue. Use this skill as the entry point for development work.
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

The Issue may remain in Inbox or Backlog.

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

`Backlog`

Do not automatically move an Issue to `Ready` unless:

- The user explicitly requested it, or
- The project workflow explicitly allows the planner to mark it Ready.

---

### Step 6: Readiness check

Before recommending `Ready`, verify:

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

`Backlog`

---

## Output

Return:

### Issue

The Issue number and title.

### Status

One of:

- Inbox
- Backlog
- Ready candidate

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