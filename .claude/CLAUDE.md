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

Every skill and agent declares a model in its frontmatter. **A frontmatter declaration is a
request, not a guarantee.** The session's model is set by the CLI (`--model`), and a skill's
declaration may or may not override it. Never assume a stage ran on the model it declares —
measure it (see **How to check what actually ran**) before relying on it.

## What each stage declares

| Kind of work | Model |
| --- | --- |
| Running commands (git, `gh`) with no judgment | `haiku` |
| Comparing simple properties (status, priority, dependency counts) | `haiku` |
| Verifying tests or inspecting Issues | `sonnet` |
| Merging a Pull Request (irreversible; gated on preconditions) | `sonnet` |
| Implementing production code | `sonnet` — see **Implementation runs on Sonnet** |
| Authoring Issues | `opus` |

Resulting assignments:

- `haiku` — `git-workflow`, `triage-backlog`, `ready-issue`
- `sonnet` — `pull-request`, `work-next`, `review-issue`, `qa-issue`, `complete-issue`; the `reviewer` and `qa` agents
- `opus` — `implement-issue`, `plan-issue`, `discover-issues`; the `implementer` agent

Two deliberate exceptions:

- `ready-issue` runs on `haiku` because its selection step is a property comparison, but it delegates the readiness evaluation to `project-planner` on `sonnet` — judging an Issue means reading and assessing it.
- The `project-planner` agent keeps `model: inherit`. It is called both for Issue creation (opus) and Issue assessment (haiku/sonnet), so the calling skill decides.

## What actually ran

Measured 2026-09-02 across the three unattended `work-next` cycles whose session IDs appear in
`~/.local/state/claude-auto/*.log` (411 assistant responses). The loop was invoking
`claude -p "/work-next" --model opus` at the time.

| Stage | Declares | Observed | Match |
| --- | --- | --- | --- |
| (no skill attributed — top-level turns) | — | `opus` ×125, 90K output tok | — |
| `work-next` | `sonnet` | `sonnet` ×92 | yes |
| `git-workflow` | `haiku` | `sonnet` ×57 | no |
| `implement-issue` | `opus` | `sonnet` ×56, `opus` ×0 | no |
| `qa-issue` | `sonnet` | `opus` ×24, `sonnet` ×7 | no |
| `review-issue` | `sonnet` | `opus` ×17, `sonnet` ×11 | no |
| `pull-request` | `sonnet` | `sonnet` ×18, `opus` ×4 | partial |
| `complete-issue` | `sonnet` | `sonnet` ×14, `opus` ×12 | partial |

Three findings, and they are why this section no longer states the assignment as fact:

1. **The CLI `--model` governed the largest block** — the top-level turns attributed to no
   skill: 125 responses and 90K output tokens, the single biggest consumer.
2. **`implement-issue` never reached `opus`** in any measured cycle, despite declaring it.
3. **`review-issue` / `qa-issue` ran mostly on `opus`**, despite declaring `sonnet` — the
   inverse of the intent.

In these cycles every response ran at `effort=medium`. Interactive sessions in the same
transcript directory show `effort=high`, so this is a property of how the loop invokes
`claude`, not a repository-wide setting.

The sample is small: session IDs have only been logged since 2026-09-02, so 3 of 24 cycles
could be attributed. Closing the gap between declaration and behaviour is #1011.

## Implementation runs on Sonnet

This section previously ended with "Never edit production code on anything below Opus." **That
rule is withdrawn.** No hook ever enforced it, and the measurement above shows it never held —
every implementation response ran on Sonnet.

By the user's decision (2026-09-02), implementation runs on **Sonnet by default**, and Opus is
reserved for escalation rather than spent up front:

- The unattended loop (`~/.local/bin/claude-work-next.sh`) invokes `--model sonnet`.
- When an Issue is rolled back from `Review` or `QA` to `In progress` **twice within one
  cycle**, the loop stops implementing, returns the Issue to `Ready`, and re-assesses its
  readiness on **Opus** in a fresh context. Repeated rollbacks are treated as evidence of a
  defective Issue definition, not of an under-powered implementation model.

Do not reinstate an "Opus only" rule for production code without also making it enforceable.
An unenforced model rule is exactly what produced the three-way mismatch above.

## How to check what actually ran

```bash
cd ~/.claude/projects/-home-seiji-src-lets-blog-server
cat *.jsonl | jq -R 'fromjson? // empty' \
  | jq -sr '[.[] | select(.type=="assistant")]
      | group_by((.attributionSkill // "(none)") + "|" + .message.model)
      | map({k: .[0], n: length}) | sort_by(-.n) | .[]
      | "\(.n)  \(.k.attributionSkill // "(none)")  \(.k.message.model)  effort=\(.k.effort)"'
```

`attributionSkill`, `message.model` and `effort` are recorded per response. A subagent's own
responses are **not** in these transcripts, so the model behind an `Agent` call cannot be
confirmed this way — that blind spot is part of #1011.

---

# Autonomous Task Execution

Once a task is started via `work-next` (or an equivalent "implement the next task" request), it must proceed through Implementation → Review → QA → Pull Request → Merge without stopping to ask the user whether to continue at each stage. The merge is a squash merge performed by `complete-issue`; once QA has passed and the Pull Request is open, it does not need a separate confirmation.

A recoverable stage outcome — implementation issues, Review `CHANGES REQUIRED`, QA `FAIL` — must loop back into implementation automatically and retry. Do not pause for user confirmation before retrying.

The workflow may still stop before the Pull Request is merged, but only for a genuine blocker:

- A requirement ambiguity only the user can resolve (Review `REQUIREMENT CLARIFICATION`, or a blocking question raised during implementation).
- QA `BLOCKED` (verification itself cannot proceed).
- A per-stage retry limit is exceeded without resolving the problem (see `work-next`).
- The Pull Request cannot be merged as-is for a reason this workflow may not fix on its own:
  a draft, or a blocked merge state (branch protection, a required check that cannot pass).
  A **merge conflict is not one of these** — resolve it on the working branch per
  **Merge Conflicts**. Never force a merge past any of them.
- A live-system mutation would require explicit confirmation (see existing Keycloak / production DB rules).
- Two well-evidenced verdicts on the same Issue disagree and only the user can settle it
  (see **Dependency Resolution** → When a verdict contradicts a recent one).

Otherwise, do not halt the workflow short of a merged Pull Request and a `Done` Issue.

---

# Read-Only Stages

This is the single definition of "read-only". `discover-issues`, `triage-backlog`, and
`ready-issue` defer to it, as does every agent they spawn. Do not restate it differently
anywhere else — if you find a second definition in `.claude/`, that is a bug to fix, not a
variant to follow.

These three stages never change the repository. They read the codebase and the Issue tracker,
form a judgment, and record that judgment in GitHub. That is their entire output.

## What must not change

While one of these stages is running, nothing in the working tree may be written — not
production code, not tests, not configuration, not migrations, not documentation, not
`.claude/` itself. "Production code" is not the boundary; the boundary is **any file in the
repository**.

Not permitted:

- `Edit`, `Write`, `NotebookEdit`
- Shell writes into a repository path: `>`, `>>`, `sed -i`, `patch`, `tee`, `mv`, `rm`, `cp`
- Commands that write as a side effect: formatters, `--fix` linters, code generators,
  dependency installs that touch a lockfile, database migrations, builds that commit artifacts
- Any `git` command that changes state: `add`, `commit`, `checkout`, `switch`, `branch`,
  `merge`, `rebase`, `stash`, `restore`, `reset`, `push`

Read-only inspection is expected and encouraged: `cat`, `sed -n`, `grep`, `find`, `git log`,
`git diff`, `git show`, `gh issue view`, `scripts/issue-dependency-status.sh`.

Scratch notes go to the session scratchpad directory, never into the repository.

## What may change

Only GitHub Issue state, and only the mutations listed for that stage:

| Stage | Permitted GitHub mutations |
| --- | --- |
| `discover-issues` | Create new Issues in `Inbox` with `Priority` set; comment on an existing Issue when the finding is already covered by it |
| `triage-backlog` | Move `Inbox → Backlog`; set `Priority` on each Issue it moves |
| `ready-issue` | Move `Backlog → Ready`; post the Readiness Report as a comment; rewrite Epic shorthand in the Issue body to `#<number>` (required by **Dependency Resolution** → Recording dependencies) |

Anything not listed is out of bounds — including closing an Issue, which stays the user's call.

## When a read-only stage finds something it wants to fix

Do not fix it. That is the point of the stage. File it — or comment on the Issue that already
covers it — per **Scope Control**, and report it. A one-line "obvious" fix is still a code
change, and a stage that is trusted to only read must actually only read.

If a stage cannot complete its judgment without changing a file, that is a blocker to report,
not a reason to make the change.

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

# Test-First Implementation

This is the single definition of how implementation proceeds. `work-next`, `implement-issue`,
the `implementer` agent, and `review-issue` all defer to it. Do not restate it differently
anywhere else — if you find a second definition in `.claude/`, that is a bug to fix, not a
variant to follow.

Every Issue is implemented test-first:

1. **RED — write the acceptance tests.** Translate the Issue's Acceptance Criteria into Gherkin
   scenarios, run them, and confirm every new scenario **fails**. Record the failure output.
2. **GREEN — change the production code.** Only after red has been recorded for the criterion
   being implemented.
3. **Repeat** in small cycles, one criterion (or one unit of behavior) at a time.

A new acceptance test that passes before any production code changed is not evidence of correct
behavior — it means the scenario does not actually exercise the criterion. Fix the scenario
until it fails, and fails for the right reason (the behavior is missing, not a typo in a
selector or a step definition). Never skip the red step, and never write it up as done without
the failure output to show for it.

## Where the tests live

| Kind | Location | Runner |
| --- | --- | --- |
| Acceptance (Gherkin) | `apps/web/e2e/features/**/*.feature` — Japanese keywords (`機能:` / `シナリオ:` / `前提` / `もし` / `ならば`); step definitions in `apps/web/e2e/steps/` | `npm run test:at` (`playwright-bdd`; stage projects `at-setup` → `at-seed` → `at-provision` → `at-main` → `at-destructive`, selected by `@stage:` tags) |
| Frontend unit | `apps/web/src/**/*.test.ts(x)` | `npm run test`, `npm run test:coverage` (jest) |
| Backend unit / integration | `services/<svc>/src/test/**` | `./gradlew :services:<svc>:test` (JUnit + JaCoCo) |

Write the Gherkin scenario wherever the criterion is reachable through the product — that is
the whole point of an acceptance test. When a criterion genuinely cannot be reached from the
web UI (an internal service contract, a migration, an operational behavior), say so explicitly
in the implementation report, name why, and express the criterion as a service-level test
instead. That is a documented exception, not a silent one.

## Never edit tests and production code in the same phase

An edit phase is either a **test phase** or a **production phase**. Never both.

- **Test phase**: only test code changes — `**/*.feature`, `apps/web/e2e/**`,
  `**/*.test.ts(x)`, `**/*.spec.ts`, `**/src/test/**`, `**/src/testFixtures/**`, and
  test-only fixtures and helpers. Production code is not touched, not even a one-character fix.
- **Production phase**: only production code changes. No test file is touched — not to adjust
  an assertion, not to fix an import, not to make something compile.

Commit each phase separately (`test: …` for a test phase; `feat:` / `fix:` / `refactor:` for a
production phase) so the alternation is visible in history. Verify it before every commit:

```bash
git diff --cached --name-only
```

Every path in that list must be on the same side of the line. If it is not, unstage and split.

When a production change makes existing tests stop compiling (a renamed method, a changed
signature), finish and commit the production phase, then do the mechanical test adaptation as
the next test phase. The phases alternate; they never merge into one edit. The branch must be
green before it is pushed.

## Coverage

Tests must cover the production code this Issue adds or changes to at least **90% C1
(branch/decision) and 90% C2 (condition)**.

- **Scope: the code this Issue changed**, not the repository as a whole. Pre-existing coverage
  debt in files you did not touch is not this Issue's problem — and is never an excuse for
  leaving new code uncovered.
- **JVM**: JaCoCo's `BRANCH` counter — `./gradlew :services:<svc>:test jacocoTestReport`,
  report at `services/<svc>/build/reports/jacoco/test/html/index.html`. The compiler
  short-circuits `&&` / `||` into separate bytecode branches, so this counter reflects
  condition coverage, not merely decision coverage.
- **Frontend**: jest's `branches` metric — `npm run test:coverage` (v8 provider), read per
  changed file, not from the global summary.
- Report the measured numbers **for the changed files**, together with the command that
  produced them. "Tests pass" is not a coverage report.
- The repository-wide thresholds (`apps/web/jest.config.ts` → `coverageThreshold`, currently
  40) are a floor for legacy code and a separate concern. Do not lower them, and do not raise
  them as a side effect of an Issue.

If a branch genuinely cannot be reached from a test, name it and say why in the implementation
report. Do not pad the number with tests that assert nothing.

## Never skip a test

A failing test is fixed, never silenced. Do not add `@Disabled`, `@Ignore`, `test.skip`,
`it.skip`, `xit`, `describe.skip`, `test.fixme`, a `@skip` / `@fixme` tag, a `--grep-invert`
exclusion, or a `testPathIgnorePatterns` entry to make a run green. Do not delete a failing
test, and do not weaken an assertion until it stops failing.

When a test fails, **fix the production code first** — a failing test is evidence about the
code until proven otherwise. Change the test only when the test case itself is demonstrably
inappropriate: it asserts behavior the Issue's Acceptance Criteria do not require, or it
encodes an assumption this Issue deliberately changed. When you do change one, report which
test, and why the old assertion was wrong.

---

# Scope Control

Do not change unrelated files.

Do not perform opportunistic refactoring unless:

- It is required to complete the issue, or
- The user explicitly requests it.

If a problem outside the issue is discovered:

Do not silently fix it.

Do not wait for the user's judgment on whether it is worth filing.

First, search for an existing Issue covering the same problem. Run `gh issue list --state open --search "<term>"` for the affected file path(s) and class/symbol name(s), and for the observable symptom. Search each identifier separately — a single combined query misses Issues that use different wording.

- If an open Issue already covers the same problem, do **not** create a new one. Add a comment to that Issue with the new evidence (where it was re-encountered, which stage found it, any detail its body lacks) and report its number instead.
- If a matching Issue exists but the new finding is genuinely broader or narrower in scope, say so explicitly in the comment, and only then decide whether a separate Issue is warranted.
- Only when no existing Issue covers it, create a new one.

Create the new GitHub Issue in `Inbox`, using the `project-planner` Issue template (Title, Background, Problem, Goal, Requirements, Acceptance Criteria, Scope, Out of Scope, Dependencies). This applies at every stage of the workflow (planning, implementation, review, QA) — whichever stage discovers the problem files it immediately.

The Issue's `Priority` field (P0/P1/P2) must be set before the Issue is considered filed. Never leave priority unset on a newly discovered Issue, even though older Issues in the project may have it unset.

Then report:

- What was discovered
- Why it matters
- Whether it blocks the current issue
- The new Issue number created for it, **or** the existing Issue number the finding was added to

---

# Completion Definition

Work is not Done merely because code has been written.

An issue may be considered complete only when:

- Acceptance criteria are satisfied
- Relevant tests pass
- Required validation has completed
- Review has no blocking issues
- QA confirms the expected behavior
- A Pull Request was opened and squash-merged into `develop`

Passing QA opens a Pull Request; it does not mark the issue Done. `complete-issue` then merges it with `gh pr merge --squash --delete-branch`, moves the Issue to `Done`, and deletes the working branch locally and remotely.

Squash is this repository's merge method for Issue Pull Requests. A Pull Request that cannot be
merged cleanly is never forced through — not with `--admin`, not with a different merge method,
not by bypassing a branch protection rule. A **merge conflict** is resolved on the working
branch and re-verified (see **Merge Conflicts**); a draft or blocked merge state that survives
that is a blocker to report.

---

# Merge Conflicts

This is the single definition of how a conflict with `develop` is handled. `pull-request`,
`complete-issue`, `git-workflow`, and `work-next` defer to it.

A conflict between the working branch and `develop` **is resolved, not reported as a blocker.**

Resolve it on the working branch:

```bash
git fetch origin
git merge origin/develop     # resolve the conflicted files, then commit
```

then re-validate and push. This applies whenever the conflict shows up — while `pull-request`
is preparing the Pull Request, or after it is open and GitHub reports
`mergeable: CONFLICTING` / `mergeStateStatus: DIRTY`.

What remains forbidden is getting the merge through *without* resolving it:

- `gh pr merge --admin`
- Any merge method other than `--squash`
- Marking a draft ready for review to unblock a merge
- Bypassing a branch protection rule

`complete-issue` still merges only a clean, mergeable Pull Request. When it finds a conflict,
the fix is to resolve it on the working branch, push, re-verify, and merge — never to force it.

## After resolving a conflict

Re-run the full relevant validation (tests, lint, type check). If anything fails:

1. **Fix the production code first.** A conflict resolution most often drops or duplicates a
   change — that is a production defect, not a test defect.
2. Change a test only when the test case itself is demonstrably inappropriate, per
   **Test-First Implementation** → Never skip a test. `@Disabled`, `test.skip`, and deleting
   the test are never the resolution.
3. The conflict-resolution commit itself may touch test and production files together — it is
   the mechanical reconciliation of two existing histories, not new authoring. Everything you
   write **after** it goes back to alternating test and production phases in separate commits.

---

# Dependency Resolution

This is the single definition of "dependencies are resolved". `work-next`, `implement-issue`,
`ready-issue`, `triage-backlog`, `plan-issue`, and the `project-planner` agent all defer to it.
Do not restate it differently
anywhere else — if you find a second definition in `.claude/`, that is a bug to fix, not a
variant to follow.

Before producing any Ready/Backlog verdict, run:

```bash
scripts/issue-dependency-status.sh <issue-number>
```

It prints the live state of every dependency the Issue records, plus any readiness verdict
already posted on the Issue. Never derive a verdict from the Issue body's prose alone, and
never carry a dependency's status over from an earlier comment — re-read it live.

## What counts as a blocker

1. **An open `blocked_by` link is the only status-based blocker.** If GitHub's formal
   dependency graph names an open Issue, the Issue is blocked. Full stop.
2. **A dependency Issue named only in prose does not block by its board status.** What decides
   readiness is whether *this* Issue's acceptance criteria can be implemented and verified
   against the codebase as it stands right now.
3. A parent or tracking Issue that is still open does **not** block when its children are done
   and the substance is in the code. Conversely, children being closed does not make an Issue
   ready when the substance is not actually there. Look at the code, not the board.
4. Every verdict must state **which** of these grounds it used, and cite the live evidence
   (the script's output, or the file paths inspected).

Rule 2 moves the check from the board to the code; it does not remove it. This repository barely
uses `blocked_by` links, so rule 2 is the path most verdicts take. A verdict that asserts "prose
dependencies do not block" without **naming the concrete files, endpoints, or config it
inspected** is not a verdict; treat it as unverified and do the inspection. The script prints the
inputs — it does not inspect the codebase for you.

## When a verdict contradicts a recent one

Governs whenever you are about to post a verdict opposite to a recent one, and decides *whether*
you may flip the status. **Reversing a verdict** below is the procedure you run first — only ever
a step inside this decision. The rules above make a verdict's *form* uniform (cited code, not
board status) but do not mechanize how to weigh competing readings of that code: two assessors
can cite real evidence and both be legitimate. After running it, apply exactly one of:

- **The earlier ground is factually falsified** — the code it cited does not say what it was
  claimed to say (e.g. a `fallback-uri` cited as covering a path that it demonstrably does not
  match). A correction, not a disagreement: flip the status and name the falsified ground.
- **Both readings are tenable on the same facts** — you weigh the same code differently rather
  than showing the earlier reading false. Do **not** flip the status. Post both readings and what
  would settle them, leave the status, and escalate to the user (a genuine blocker — see
  **Autonomous Task Execution**). Ping-ponging well-evidenced opposite verdicts is worse than one
  open question (#751).
- **Inspection is inconclusive** — default to `Backlog`. Ready authorizes starting without
  further clarification; without establishing the substance, you lack that authorization.

If you cannot tell which of the first two you are in, you are in the second. Silently picking a
side is what this prevents.

## Reversing a verdict

Required by the section above; its outcome feeds that choice. Never post a contradicting
verdict without running it.

1. Read the existing readiness comments (the script lists them).
2. Re-check each ground the previous verdict cited, live.
3. Say in the new comment which specific ground no longer holds, and why.

If every ground still holds, the disagreement is about the *definition* above, not the facts —
apply the definition rather than posting a contradicting verdict.

## Recording dependencies

Record dependencies as resolvable identifiers: a GitHub `blocked_by` link, or `#<number>` in
the body. Epic shorthand (`A4`, `B6`, `C14`) is not resolvable — it forces every run to
re-translate labels into Issue numbers, and that translation is where verdicts diverge.
When an Issue records dependencies only as shorthand, resolve them to numbers and update the
body before judging readiness. If they cannot be resolved, say the dependencies are
*unidentifiable* — do not assert they are *unresolved*.

---

# Enforcement

The rules above are not only written down; the ones that can be checked mechanically are
**enforced**. A violation is refused, not reported.

## Claude Code hooks — `.claude/settings.json`

`.claude/hooks/guard.py` runs as a `PreToolUse` hook and denies the tool call outright.

| Guard | Fires on | Blocks |
| --- | --- | --- |
| Read-only stage tracking | `Skill` | Records that `discover-issues` / `triage-backlog` / `ready-issue` started; cleared by any other skill or by the user's next prompt |
| Repository writes | `Write` / `Edit` / `NotebookEdit` | Any write inside the repository while a read-only stage is active |
| Mutating shell | `Bash` | `sed -i`, `rm` / `mv` / `cp` / `tee` / `patch`, state-changing `git`, dependency installs, and output redirection — while a read-only stage is active |
| Test silencing | `Write` / `Edit` | Adding `@Disabled`, `@Ignore`, `test.skip`, `it.skip`, `xit`, `test.fixme`, or `testPathIgnorePatterns` to a test or production file (`.claude/`, `docs/`, `scripts/`, `.github/` and `*.md` are exempt, so the rules themselves can be written down) |
| Phase separation | `Bash` (`git commit`) | A commit whose staged paths mix test code and production code |
| Hook bypass | `Bash` | `git commit` / `git push` with `--no-verify` — the git hook is not optional |
| Merge method | `Bash` (`gh pr merge`) | `--admin`, `--merge`, `--rebase` |
| Coverage | `Bash` (`gh pr create`) | Opening a Pull Request while changed-code C1/C2 coverage is under 90% |

`.claude/hooks/paths.py` is the single classifier for test / production / neutral paths. Both
the Claude Code hook and the git hook import it; do not restate the patterns anywhere else.

## Git hook — `scripts/git-hooks/pre-commit`

Bound with `git config core.hooksPath scripts/git-hooks` (already set in this checkout; a fresh
clone runs it once). It enforces the same invariants for **any** committer, agent or human:

1. **Phase separation** — no commit mixes test and production paths.
2. **No test silencing** — nothing that disables a test is added to a test or production file.
3. **Test-first** — a commit containing production code is refused while the branch has no test
   change at all. Write the failing Gherkin scenario first.

## Coverage check — `scripts/check-changed-coverage.py`

Computes branch coverage (C1/C2) of the production files this branch changed, from JaCoCo's
`BRANCH` counter and jest's branch map, and exits non-zero below 90%. Run it directly, or let
the Pull Request guard run it:

```bash
./gradlew :services:<svc>:test jacocoTestReport
cd apps/web && npm run test:coverage
python3 scripts/check-changed-coverage.py
```

A missing coverage report for a changed file fails the check — it never passes silently.

## When a guard blocks something

The guard is the rule speaking, not an obstacle to route around. Do not disable a hook, do not
reach for `--no-verify`, and do not move a file to dodge a path pattern. If a guard is
genuinely wrong, say so and file an Issue against it — the fix belongs in the guard, as its own
change.

---

# Learning Loop

When a failure, repeated review issue, or process problem is discovered:

1. Determine whether it is a one-time mistake or a recurring pattern.
2. If recurring, propose a rule or documentation improvement.
3. Do not silently modify project rules without explaining the reason.

The goal is to improve the system so the same category of mistake becomes less likely.
