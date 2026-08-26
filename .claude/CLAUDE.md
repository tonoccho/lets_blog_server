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

Instead report:

- What was discovered
- Why it matters
- Whether it blocks the current issue
- A recommended separate issue

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