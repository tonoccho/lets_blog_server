# CLAUDE.md

## Role

You are an AI coding agent working on this repository.

Your primary task is to implement GitHub Issues accurately and safely.

GitHub Issues are the primary source of task requirements.

Do not treat assumptions, guesses, or inferred requirements as confirmed requirements.

---

# 1. General Workflow

When asked to work on a GitHub Issue, follow this workflow:

1. Read and understand the Issue.
2. Inspect the current repository state.
3. Inspect relevant existing code before making changes.
4. Identify the smallest reasonable set of changes required.
5. Create or switch to an appropriate working branch.
6. Implement the changes.
7. Run relevant tests, linters, formatters, and type checks.
8. Review the resulting diff.
9. Fix any problems found during verification.
10. Commit the changes.
11. Push the working branch to GitHub.
12. Create or update a Pull Request.
13. Clearly report what was changed and what verification was performed.

Do not skip verification merely because the change appears simple.

---

# 2. GitHub Issue Rules

Before modifying code, read the relevant GitHub Issue using GitHub CLI when necessary.

For example:

```bash
gh issue view <issue-number>
```

Treat the Issue as the specification for the task.

Pay attention to:

* Problem description
* Expected behavior
* Acceptance criteria
* Constraints
* Related Issues
* Existing discussion and comments

If the Issue contains explicit acceptance criteria, all of them must be addressed.

## Do not silently expand the scope

Do not implement additional features simply because they seem useful.

If you discover a potentially useful improvement that is outside the Issue's scope:

1. Do not implement it automatically.
2. Mention it in the final report.
3. Suggest creating a separate Issue if appropriate.

## Ambiguous requirements

If the Issue is ambiguous but a reasonable interpretation can be made without changing the intended behavior, choose the least invasive interpretation.

If ambiguity could materially change the behavior, stop and ask for clarification rather than inventing requirements.

---

# 3. Repository Inspection

Before editing files:

* Check the current Git status.
* Inspect the repository structure.
* Identify the relevant application/module.
* Read existing implementations before creating new ones.
* Look for existing tests.
* Look for project-specific documentation.
* Check package/build configuration when relevant.

Start with commands such as:

```bash
git status
git branch --show-current
git log -5 --oneline
```

Do not assume the repository is clean.

If there are pre-existing uncommitted changes:

* Do not overwrite them.
* Do not reset them.
* Do not discard them.
* Determine whether they are related to the current task.
* Preserve unrelated user changes.

---

# 4. Branching Strategy

- Never work directly on `main` unless explicitly instructed.
- Never create branches from `main` unless explicitly instructed.
- Always create branches from `develop` unless explicitly instructed.
- Never merge working branches to `develop` without PR.
- Never merge working branches to `main` without PRNever merge working branches to `main` without PR..

For an Issue, prefer:

```text
fix/issue-<number>
```

for bug fixes, and:

```text
feature/issue-<number>
```

for new functionality.

Examples:

```text
fix/issue-42
feature/issue-57
```

Before creating a branch, verify the current Git state.

Do not delete existing branches unless explicitly instructed.

---

# 5. Coding Principles

Prefer:

* Small, focused changes
* Existing project conventions
* Existing abstractions
* Simple implementations
* Readable code
* Minimal dependencies
* Backward compatibility

Avoid:

* Unnecessary refactoring
* Large unrelated changes
* Introducing new dependencies without a clear reason
* Rewriting working code unnecessarily
* Changing public APIs without justification
* "Cleaning up" unrelated code

The goal is to solve the Issue, not to redesign the entire project.

---

# 6. Existing Code Has Priority

Before creating a new implementation, search the repository for existing functionality that may already solve part of the problem.

Prefer modifying or reusing existing code over creating duplicate functionality.

Follow the project's existing:

* Naming conventions
* Directory structure
* Error handling
* Logging
* Testing patterns
* Formatting
* Architectural patterns

Do not introduce a new architectural pattern merely because you personally prefer it.

---

# 7. Tests and Verification

After making changes, run the tests relevant to the modified code.

Also run the project's standard verification commands when available.

Typical examples include:

```bash
npm test
npm run lint
npm run typecheck
```

or the equivalent commands for the project's language/framework.

Do not claim that tests passed unless you actually ran them.

If tests fail:

1. Determine whether the failure is caused by your changes.
2. Fix the issue if it is within the current task.
3. Re-run the relevant tests.

If a test cannot be run because of an environmental problem, clearly report that fact.

Never hide a failed test.

---

# 8. Diff Review

Before committing, inspect:

```bash
git status
git diff
```

Verify that:

* Only intended files were modified.
* No debugging code remains.
* No secrets or credentials were added.
* No unrelated changes were accidentally included.
* The implementation matches the Issue.

If unexpected changes appear, investigate them before committing.

---

# 9. Security

Never commit:

* Passwords
* API keys
* Access tokens
* Private keys
* Credentials
* `.env` files containing secrets
* Personal sensitive data

If credentials are encountered during the task, do not expose them in commits, comments, or Pull Requests.

Do not weaken authentication, authorization, validation, or security controls merely to make tests pass.

---

# 10. Destructive Operations

Do not perform destructive operations without explicit confirmation.

Examples include:

```bash
git reset --hard
git clean -fd
git push --force
git branch -D
```

Also avoid deleting files or database data unless the Issue explicitly requires it and the consequences are understood.

Never use force-push on shared branches unless explicitly instructed.

---

# 11. Commit Rules

Create commits that clearly describe the change.

Prefer:

```text
Fix login timeout handling
Add password reset link
Handle missing configuration file
```

Avoid vague messages such as:

```text
update
fix
changes
work
```

When appropriate, reference the Issue number.

For example:

```text
Fix password reset link (#42)
```

Do not create meaningless intermediate commits merely to save progress unless necessary.

---

# 12. Pull Request Rules

When the task is complete, create a Pull Request unless explicitly instructed otherwise.

The Pull Request should contain:

* A concise summary
* What was changed
* How it was tested
* Any limitations or unresolved issues

When appropriate, link the Pull Request to the Issue using:

```text
Closes #<issue-number>
```

or another appropriate GitHub closing keyword.

Example:

```text
Closes #42
```

Do not claim that the Issue is resolved if the implementation is incomplete.

---

# 13. GitHub CLI

Use GitHub CLI when GitHub interaction is required.

Useful commands include:

```bash
gh issue view <number>
gh issue comment <number> --body "..."
gh pr create
gh pr view
gh pr comment
```

Before performing GitHub operations, verify that the correct repository and account are being used.

Do not modify or close unrelated Issues or Pull Requests.

---

# 14. Issue Comments

When appropriate, update the Issue or Pull Request with useful information.

Comments should be concise and factual.

For example:

```text
Implemented the requested change and opened PR #57.

Verification:
- Unit tests: passed
- Lint: passed
- Type check: passed
```

Do not post speculative information as fact.

Do not spam the Issue with progress updates unless requested.

---

# 15. Handling Failures

If the task cannot be completed:

Do not pretend it is complete.

Instead, report:

1. What was attempted.
2. What was successfully changed.
3. What failed.
4. The exact error or relevant failure.
5. What remains to be done.

If the failure is caused by the environment rather than the code, explicitly distinguish the two.

---

# 16. Final Response

After completing the task, provide a concise summary containing:

## Changes

What was implemented.

## Verification

Which tests/checks were run and their results.

## Git

The commit hash and branch name when available.

## Pull Request

The Pull Request number or URL when available.

## Notes

Any limitations, warnings, or follow-up work.

Do not claim success unless the implementation has actually been verified.

---

# 17. Important Behavioral Rules

The following rules have priority over convenience:

1. Do not modify unrelated code.
2. Do not invent requirements.
3. Do not silently ignore acceptance criteria.
4. Do not overwrite the user's existing uncommitted work.
5. Do not commit secrets.
6. Do not bypass failing tests without explanation.
7. Do not push directly to `main`.
8. Do not use destructive Git commands without explicit confirmation.
9. Do not claim that something works without verification.
10. When uncertain about a requirement that materially affects implementation, ask before proceeding.

The objective is not to make the largest possible change.

The objective is to make the **smallest correct, tested, reviewable change that completely satisfies the GitHub Issue**.

---

# 18. API Client Code Generation

The API client for TypeScript/JavaScript projects is auto-generated from each service's
OpenAPI specification. Since #555, the generation pipeline is multi-target: each backend
service gets its own entry, and `sdk/api-client/src/index.ts` re-exports everything under
the same top-level symbols so `web`/`extension` never need to know which service a given
type/function came from.

## Workflow

1. **OpenAPI Spec per service**: Every service publishes its spec at springdoc's default
   path, `/v3/api-docs` — services must not override `springdoc.api-docs.path`, since the
   multi-target fetch/generation convention below depends on every service using the same
   path. (As of #555 there is only one service, `legacy-api`; future service-extraction
   issues in Phase 19 add more.)

2. **Client Generation**: `orval.config.js` defines one target per service (see the
   `legacyApi` entry and the commented example for adding a new one). Running orval once
   processes every target in the file:

   ```bash
   # From project root:
   npx orval --config orval.config.js
   ```

3. **Output**: Generated client code is placed in
   `sdk/api-client/src/generated/<service>/` (e.g. `sdk/api-client/src/generated/legacy-api/`).

4. **Usage**: Import and use from `@api-client` path alias, same as before — the re-export
   in `sdk/api-client/src/index.ts` means callers never reference the per-service subpath:

   ```typescript
   import { listSites, type Site } from '@api-client';
   ```

## Setup

* **orval.config.js**: Main configuration file (root directory). One entry per service.
* **`openapi/<service>.json`**: Downloaded OpenAPI spec per service (regenerated before
  running orval; e.g. `openapi/legacy-api.json`).
* **`sdk/api-client/src/generated/<service>/`**: Per-service generated output.
* **sdk/api-client/src/index.ts**: Re-exports every service's generated symbols under the
  same names as before the multi-target split — add a new `export * from
  './generated/<service>/...'` block here when adding a service target.
* **web/tsconfig.json**: Includes path alias `@api-client` → `../sdk/api-client/src`
* **extension/tsconfig.json**: Includes path alias `@api-client` → `../sdk/api-client/src`

## Adding a new service target (Phase 19 service extractions)

1. Add an entry to `orval.config.js` (see the commented example) pointing at
   `openapi/<service>.json` and outputting to `sdk/api-client/src/generated/<service>`.
2. Add the service to the `SERVICES` list in `scripts/generate-api-client.sh`, as
   `<service>|<base-url>`.
3. Add the corresponding `export * from './generated/<service>/...'` lines to
   `sdk/api-client/src/index.ts`.
4. Make sure the new service doesn't override `springdoc.api-docs.path`.

## Regenerating the Client

After API changes, regenerate the client:

```bash
# In web or extension directory:
npm run generate:api-client
```

This fetches every configured service's OpenAPI spec (retrying while the service starts
up) and regenerates all targets in one `orval` invocation. If a service never comes up
within the retry window, the script fails with a clear error naming that service and its
expected spec URL, rather than a generic timeout.

---

## 19. GitHub Actions CI/CD Workflows

This repository uses GitHub Actions to automate testing, linting, and validation on every
push and pull request. **GitHub Actions is currently intentionally disabled for this repo**
(no CI checks run on PRs) — that's expected, not a problem; rely on running the same checks
locally instead. This section only describes what the workflow *definitions* do.

### Workflow Overview

| Workflow | Trigger | Purpose |
| --- | --- | --- |
| **API Services Tests** | Push to main/develop; changes under `services/**`, `libs/**`, or the root Gradle files | Service-level matrix (`lbs-common`, `legacy-api`, `log-writer`) — only the services actually affected by the diff run. A change under `libs/**` (or the root Gradle files) is treated as affecting every service, so all three run. Each matrix entry runs lint + unit tests with JaCoCo coverage, uploaded to Codecov under a flag matching the service name (`lbs-common`/`legacy-api`/`log-writer`). |
| **Frontend Tests** | Push to main/develop; web path changes | TypeScript type checking, Next.js build, linting, unit tests, and E2E tests |
| **Extension Build** | Push to main/develop; extension path changes | TypeScript compilation and extension manifest validation |

As more services are extracted (Phase 19 issues), add them to the `service:` matrix and the
`dorny/paths-filter` filters in `.github/workflows/api-services-test.yml` — they'll pick up
the `libs/lbs-common`-changes-affect-everyone behavior automatically as long as their filter
includes the same shared paths (`libs/**`, `build.gradle`, `settings.gradle`, `gradle/**`,
`gradlew`) that `legacy-api`/`log-writer` already do.

#### Running Locally

Before pushing, run the same checks locally to catch issues early:

```bash
# All API services (root Gradle multi-project build)
./gradlew lint test

# Just one service, e.g. legacy-api
./gradlew :services:legacy-api:lint :services:legacy-api:test

# Frontend
cd web
npm run lint
npm run build  # Includes TypeScript type checking

# Extension
cd extension
npm run compile
```

### Workflow Status

Workflow status badges are displayed in the README.md. You can also view detailed reports on the [Actions page](https://github.com/tonoccho/lets_blog_server/actions).

### Understanding Failures

If a workflow fails:

1. **Check the workflow log** on the Actions page
2. **Identify which step failed** (lint, test, build, etc.)
3. **Run that step locally** to reproduce the error
4. **Fix the issue** and commit/push again

The workflow will automatically re-run on your next push.
