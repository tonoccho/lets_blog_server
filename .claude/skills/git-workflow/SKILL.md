---
name: git-workflow
description: Standard Git workflow for issue-based development, including repository safety checks, branch naming, commits, validation before push, and safe remote operations.
---

# Git Workflow Skill

## Purpose

Use this skill whenever work involves:

- Starting implementation for a GitHub Issue
- Creating a branch
- Checking repository state
- Committing changes
- Pushing changes

The primary goal is to prevent unrelated work, accidental data loss, and invalid commits from entering the repository.

---

# Core Safety Rules

Never automatically:

- Discard user changes
- Run destructive Git commands without confirmation
- Force push
- Reset unrelated work
- Rewrite existing history
- Commit secrets
- Commit unrelated changes
- Stash user changes unless explicitly instructed

Examples of commands requiring special care include:

- `git reset --hard`
- `git clean -fd`
- `git push --force`

Do not use these commands as part of the normal workflow.

---

# 1. Inspect Repository State

Before starting work, inspect:

- `git status`
- `git branch --show-current`

Also inspect the repository configuration when necessary.

Determine:

- Current branch
- Whether the working tree is clean
- Whether untracked files exist
- Whether the repository is already in the middle of another operation

Do not assume the working tree is safe.

---

# 2. Handle Existing Changes

If unrelated changes are present:

- Do not discard them.
- Do not commit them.
- Do not silently stash them.
- Stop and report the situation.

Normal `/work-next` execution requires a clean working tree.

Example report:

Cannot start `/work-next` safely.

Current branch:
`feature/88-existing-work`

Uncommitted changes were detected.

No branch was created and no changes were modified.

---

# 3. Determine the Base Branch

For this repository, working branches are created from:

`develop`

not `main`. All changes to `main` go through a Pull Request from `develop` (or a branch merged into `develop`) — never branch directly from `main` and never push directly to `main`.

If working in a different repository without this convention, determine the base branch from:

1. Existing repository configuration (e.g. `git remote show origin` → HEAD branch)
2. Project documentation
3. Existing conventions

Do not blindly assume `main` if the repository clearly uses another default branch.

---

# 4. Update the Base Branch

Before creating a working branch:

1. Switch to the base branch.
2. Fetch the latest remote state.
3. Update the local base branch safely.

Typical flow:

`git switch main`

`git pull --ff-only origin main`

Use the appropriate base branch when it is not `main`.

Prefer fast-forward-only updates when possible.

If the update cannot be completed safely, stop and report the reason.

Do not resolve unrelated merge conflicts automatically.

---

# 5. Branch Naming

All Issue branches must include the Issue number.

Format:

`<type>/<issue-number>-<short-description>`

Examples:

- `feature/123-add-tag-search`
- `fix/124-handle-login-error`
- `refactor/125-cleanup-service`
- `docs/126-update-setup-guide`
- `test/127-add-auth-tests`
- `chore/128-update-dependencies`

Rules:

- Use lowercase.
- Use hyphens.
- Keep descriptions concise.
- Include the Issue number.
- Do not use spaces.
- Do not include unnecessary implementation details.

Choose the branch type based on the Issue.

Preferred values:

- `feature`
- `fix`
- `refactor`
- `docs`
- `test`
- `chore`

---

# 6. Create the Branch

Create the branch from the updated base branch.

Example:

`git switch -c feature/123-add-tag-search`

Confirm:

`git branch --show-current`

The reported branch must match the expected Issue branch.

Do not continue implementation if the branch is incorrect.

---

# 7. Inspect Changes Before Commit

Before every commit, inspect:

- `git status`
- `git diff`

When appropriate, also inspect staged changes:

- `git diff --cached`

Confirm:

- Every change belongs to the current Issue.
- No secrets are present.
- No temporary files are included.
- No unrelated changes are staged.
- The diff is understandable and intentional.

If unrelated changes exist, do not automatically commit them.

---

# 8. Validation Before Commit

Run the validation commands defined by the project.

Prefer project-defined commands.

Common categories:

- formatter
- lint
- typecheck
- unit tests
- integration tests
- build

Do not invent commands if the repository already documents them.

Determine the correct commands from:

- `package.json`
- Project documentation
- Existing CI configuration
- `CLAUDE.md`

Record which validations were actually run.

Do not report unexecuted validation as successful.

---

# 9. Commit Rules

Follow the repository's existing commit convention.

If no convention exists:

`<type>: <summary>`

Examples:

- `feat: add article tag search`
- `fix: handle invalid search parameters`
- `refactor: simplify article query logic`
- `test: add search edge case coverage`
- `docs: update development instructions`
- `chore: update development dependency`

Rules:

- Use imperative summaries where appropriate.
- Keep the summary concise.
- Describe what changed.
- Do not include Issue numbers unless the project convention requires them.

Create commits that represent meaningful changes.

Do not create empty commits.

---

# 10. Push Rules

Before pushing:

1. Confirm validation passed.
2. Confirm the correct branch is checked out.
3. Confirm the final diff is intentional.
4. Confirm no unrelated files are staged or committed.

Push normally.

For a new branch:

`git push -u origin <branch-name>`

Never use:

`git push --force`

unless explicitly instructed by the user.

After pushing, confirm that the remote branch is available.

---

# 11. Failure Handling

If a Git operation fails:

1. Do not immediately try destructive recovery.
2. Inspect the error.
3. Determine the repository state.
4. Preserve existing work.
5. Report the failure if safe automatic recovery is not possible.

Never solve an unknown Git state with:

- `git reset --hard`
- `git clean -fd`

unless explicitly instructed.

---

# Completion Criteria

The Git portion of an Issue workflow is complete when:

- The correct Issue branch exists.
- All changes belong to the selected Issue.
- Required validation passed.
- Meaningful commits were created.
- The branch was pushed successfully.
- The remote branch is available for Pull Request creation.