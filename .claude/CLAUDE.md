# Project Development Rules

## Core Principle

This project uses a structured AI-assisted development workflow.

Do not jump directly from an informal user request to code implementation.

All feature development should follow:

1. Requirement clarification
2. Issue definition
3. Ready
4. Analysis
5. Implementation planning
6. Implementation
7. Review
8. QA
9. Done
10. Retrospective and rule improvement

---

# Source of Truth

GitHub Issues are the source of truth for development work.

Do not create independent TODO files for issue status management.

Do not begin implementation unless the task is sufficiently defined.

The expected workflow is:

Inbox
→ Backlog
→ Ready
→ In Progress
→ Review
→ QA
→ Done

---

# Agent Responsibilities

## project-planner

Responsible for:

- Understanding user requests
- Clarifying requirements
- Identifying missing requirements
- Investigating the existing project when necessary
- Defining scope
- Defining acceptance criteria
- Identifying dependencies
- Creating or updating implementation-ready issues

Must NOT:

- Implement production code
- Make architectural changes without documenting them
- Mark an issue Ready when important requirements are unknown

---

## implementer

Responsible for:

- Reading the issue
- Investigating the existing codebase
- Identifying affected areas
- Creating an implementation plan
- Implementing the approved scope
- Adding or updating tests
- Running relevant validation

Must NOT:

- Expand scope without justification
- Implement unrelated refactoring
- Change product requirements
- Mark work Done

---

## reviewer

Responsible for independently reviewing implementation.

Review:

- Requirement compliance
- Architecture consistency
- Code quality
- Security concerns
- Regression risk
- Unnecessary complexity
- Scope creep
- Missing tests

Reviewer should not approve implementation merely because tests pass.

---

## qa

Responsible for validating the completed behavior from the user's perspective.

QA must verify acceptance criteria.

QA should focus on:

- Expected user behavior
- Edge cases
- Regression behavior
- Error handling
- End-to-end flows where appropriate

QA does not assume implementation is correct.

---

# Model Selection

Coding/implementation tasks (the `implementer` agent, and any direct production-code editing) must use the Opus model.

Planning, review, and QA agents are not coding tasks and may continue to inherit the orchestrator's model.

---

# Autonomous Task Execution

Once a task is started via `work-next` (or an equivalent "implement the next task" request), it must proceed through Implementation → Review → QA → Pull Request without stopping to ask the user whether to continue at each stage.

A recoverable stage outcome — implementation issues, Review `CHANGES REQUIRED`, QA `FAIL` — must loop back into implementation automatically and retry. Do not pause for user confirmation before retrying.

The workflow may still stop before a Pull Request exists, but only for a genuine blocker:

- A requirement ambiguity only the user can resolve (Review `REQUIREMENT CLARIFICATION`, or a blocking question raised during implementation).
- QA `BLOCKED` (verification itself cannot proceed).
- A per-stage retry limit is exceeded without resolving the problem (see `work-next`).
- A live-system mutation would require explicit confirmation (see existing Keycloak / production DB rules).

Otherwise, do not halt the workflow short of an opened Pull Request.

---

# Implementation Rules

Before editing code:

1. Read the relevant GitHub Issue.
2. Read relevant architecture and development documentation.
3. Inspect existing implementations.
4. Prefer existing patterns over inventing new ones.
5. Identify the smallest change that satisfies the requirements.

After editing code:

1. Run relevant tests.
2. Run linting where available.
3. Run type checks where available.
4. Check for unintended changes.
5. Compare the implementation against acceptance criteria.

---

# Scope Control

Do not change unrelated files.

Do not perform opportunistic refactoring unless:

- It is required to complete the issue, or
- The user explicitly requests it.

If a problem outside the issue is discovered:

Do not silently fix it.

Do not wait for the user's judgment on whether it is worth filing.

Immediately create a new GitHub Issue for it in `Inbox`, using the `project-planner` Issue template (Title, Background, Problem, Goal, Requirements, Acceptance Criteria, Scope, Out of Scope, Dependencies). This applies at every stage of the workflow (planning, implementation, review, QA) — whichever stage discovers the problem files it immediately.

The Issue's `Priority` field (P0/P1/P2) must be set before the Issue is considered filed. Never leave priority unset on a newly discovered Issue, even though older Issues in the project may have it unset.

Then report:

- What was discovered
- Why it matters
- Whether it blocks the current issue
- The new Issue number created for it

---

# Completion Definition

Work is not Done merely because code has been written.

An issue may be considered complete only when:

- Acceptance criteria are satisfied
- Relevant tests pass
- Required validation has completed
- Review has no blocking issues
- QA confirms the expected behavior
- A Pull Request was opened and the user has confirmed it was merged

Passing QA opens a Pull Request; it does not mark the issue Done. Done happens only after the user confirms the merge, at which point the working branch is deleted locally and remotely.

---

# Learning Loop

When a failure, repeated review issue, or process problem is discovered:

1. Determine whether it is a one-time mistake or a recurring pattern.
2. If recurring, propose a rule or documentation improvement.
3. Do not silently modify project rules without explaining the reason.

The goal is to improve the system so the same category of mistake becomes less likely.