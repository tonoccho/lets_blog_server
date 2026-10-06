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

GitLab Issues are the source of truth for development work.

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

## How status is represented

This is the single definition of how an Issue's status is stored and changed; every skill and
agent defers to it. A second definition in `.claude/` is a bug to fix.

Status is a **label**, named `status::<Stage>`:

`status::Inbox` `status::Backlog` `status::Ready` `status::In Progress` `status::Review`
`status::QA` `status::Done`

Priority is a label too: `priority::P0` / `priority::P1` / `priority::P2`.

GitLab CE has no scoped labels (Premium), so **nothing stops an Issue from carrying two status
labels at once, or none.** It is only a convention: exactly one `status::` label, always.

## How to change status

Remove the old label and add the new one **in the same call**. Never as two calls:

```bash
glab api "projects/:id/issues/<iid>" --method PUT \
  -f "remove_labels=status::Ready" -f "add_labels=status::In Progress"
```

Two calls leave the Issue with zero or two status labels in between; one with none is invisible to
`work-next` and triage. Never write the whole label set with `labels=` — that silently drops `epic`,
`bug` and every other label. Only `add_labels` / `remove_labels`.

To read the current status:

```bash
glab issue view <iid> -F json --jq '[.labels[] | select(startswith("status::"))]'
```

### How "exactly one" is enforced

In two layers, because neither sees what the other sees:

| Layer | Catches | Blind to |
| --- | --- | --- |
| `guard.py` → `check_status_label_integrity` | agent and CLI label changes, at the moment of the mistake | the GitLab web UI, and shell indirection |
| `scripts/check-issue-labels.sh` | anything, including web-UI edits | only after the fact |

The hook refuses exactly two things, both decidable from the command text alone (no network):

- `labels=` — a wholesale overwrite, which silently drops `epic`, `bug` and everything else.
- adding a `status::` without removing one in the same call, or removing without adding.

Creating an Issue (`glab issue create --label status::Inbox,...`) is not a transition and is not
refused.

**The one exception to every new Issue starting in `Inbox`**: `/report-bug`
(`.claude/skills/report-bug/SKILL.md`) creates its Issue directly in `status::Backlog`, skipping `Inbox`,
because report-bug already investigates the codebase and fills in the Acceptance Criteria before
filing — the same judgment `triage-backlog` would otherwise make. This is still Issue creation,
not a `status::` transition, so the Legal Transitions table below is unaffected. `plan-issue`
Step 5's "the project workflow explicitly allows the planner to skip a stage" refers to this
definition; no other skill has this allowance.

Run the script when you suspect drift and after any web-UI editing:

```bash
scripts/check-issue-labels.sh
```

It reports `0` and `2+` separately; **zero is the dangerous one**, since such an Issue is invisible to
`work-next` and to triage. Rationale: `docs/WORKFLOW_RULE_RATIONALE.md` → 「ちょうど1つ」の強制が2層である理由.

### Legal Transitions

This is the single definition of which `status::` → `status::` transitions are legitimate.
`guard.py` → `check_status_label_integrity` encodes this table as `(from, to)` tuples; if the table
changes, update `guard.py` to match.

The uniqueness check does not catch a transition that skips stages (#1031).

| Kind | Transition | Driven by |
| --- | --- | --- |
| Forward | `Inbox → Backlog` | `triage-backlog` |
| Forward | `Backlog → Ready` | `ready-issue` |
| Forward | `Ready → In Progress` | `work-next` Step 6 |
| Forward | `In Progress → Review` | `work-next` Step 7 |
| Forward | `Review → QA` | `review-issue` APPROVED |
| Forward | `QA → Done` | `complete-issue`, after confirming the merge |
| Rollback | `Review → In Progress` | `review-issue` CHANGES REQUIRED |
| Rollback | `QA → In Progress` | `qa-issue` FAIL |
| Rollback | `Ready → Backlog` | `work-next` Step 4 |
| Rollback | `Review → Backlog` | `review-issue` REQUIREMENT CLARIFICATION |
| Rollback | `In Progress → Ready` | re-assessment after two rollbacks in one cycle (see **Implementation runs on Sonnet**) |
| Marker-gated | `Inbox → Done` | `close-epic` (an `epic` Issue) or `close-issue` (any other Issue), only while that skill's read-only stage marker is set (#1625, #1659) |
| Marker-gated | `Backlog → Done` | `close-issue`, only while its read-only stage marker is set (#1659) |
| Marker-gated | `Ready → Done` | `close-issue`, only while its read-only stage marker is set (#1659) |

A transition limited to one read-only stage's marker is not in `LEGAL_STATUS_TRANSITIONS`; `guard.py` keeps it in a separate constant, `MARKER_GATED_STATUS_TRANSITIONS` (`{("Inbox", "Done"): {"close-epic", "close-issue"}, ("Backlog", "Done"): {"close-issue"}, ("Ready", "Done"): {"close-issue"}}` — each value is the set of skills allowed), and `check_status_label_integrity(command, payload)` allows it only when `read_stage(payload)` is in that set. The marker lives until the user's next plain message (unchanged).

Every skill that changes a `status::` label was checked against this table. **Default is
reject, not warn**: an unlisted transition is refused by `guard.py`. A new transition goes here and
into `guard.py`'s `LEGAL_STATUS_TRANSITIONS` in the same change — never work around the guard. Only the (from, to) pair of a single paired `remove_labels=` / `add_labels=` call is
checked, from command text alone; historical transitions are not validated.
Rationale: `docs/WORKFLOW_RULE_RATIONALE.md` → Legal Transitions.

### Merge precondition (#1031)

`complete-issue` reads the Issue's `status::` label before merging, but that is a procedure, not a
guarantee (#959 was merged while `status::In Progress`). The detection is
`scripts/check-issue-labels.sh`: it flags a closed Issue that a **merged** Merge Request closed
(`issues/<iid>/closed_by`) yet lacks `status::Done`, considering only Issues closed on or after
`2026-09-03T02:44:25Z` (#1023's merge), and paginating both list queries to completion. `guard.py`
stays network-free. Rationale: `docs/WORKFLOW_RULE_RATIONALE.md` → Merge precondition.

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

Reviewer should not approve merely because tests pass.

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

Every skill and agent declares a model in its frontmatter. **A declaration is a request, not a
guarantee**: the CLI's `--model` sets the session's model. Never assume a stage ran on the model it
declares — measure it (see **What actually ran**).

## What each stage declares

| Kind of work | Model |
| --- | --- |
| Running commands (git, `gh`) with no judgment | `haiku` |
| Comparing simple properties (status, priority, dependency counts) | `haiku` |
| Verifying tests or inspecting Issues | `sonnet` |
| Merging a Merge Request (irreversible; gated on preconditions) | `sonnet` |
| Implementing production code | `sonnet` — see **Implementation runs on Sonnet** |
| Authoring Issues | `opus` |

Resulting assignments:

- `haiku` — `git-workflow`, `triage-backlog`, `ready-issue`
- `sonnet` — `merge-request`, `work-next`, `review-issue`, `qa-issue`, `complete-issue`,
  `implement-issue`, `close-epic`, `close-issue`; the `reviewer`, `qa` and `implementer` agents
- `opus` — `plan-issue`, `discover-issues`, `report-bug`

Two deliberate exceptions:

- `ready-issue` runs on `haiku` (its selection is a property comparison) but delegates the readiness evaluation to `project-planner` on `sonnet`.
- The `project-planner` agent keeps `model: inherit`; the calling skill decides.

## What actually ran

Measure, do not assume: a 2026-09-02 measurement found every stage but `work-next` running on a
model it had not declared. The table, findings and reproducing `jq` command are in
`docs/WORKFLOW_RULE_RATIONALE.md` → モデル選択. Closing the gap is #1011.

## Implementation runs on Sonnet

By the user's decision (2026-09-02), the old "Opus only for production code" rule is withdrawn (it
was never enforced). Implementation runs on **Sonnet by default**; Opus is reserved for escalation:

- The unattended loop invokes `--model sonnet --effort medium`. The two runners,
  `~/.local/bin/claude-auto-cycle.sh` (discover → triage → ready → work-next) and
  `~/.local/bin/claude-auto-queue.sh` (a fixed list of Issues), live outside this repository.
- When an Issue is rolled back from `Review` or `QA` to `In progress` **twice within one
  cycle**, the loop stops implementing, returns the Issue to `Ready`, and re-assesses its
  readiness on **Opus** in a fresh context. Repeated rollbacks are treated as evidence of a
  defective Issue definition, not of an under-powered implementation model.

Do not reinstate an "Opus only" rule without making it enforceable.

---

# Autonomous Task Execution

Once a task is started via `work-next` (or an equivalent request), it proceeds through Implementation → Review → QA → Merge Request → Merge without asking the user whether to continue. The merge is a squash merge by `complete-issue`; once QA has passed and the Merge Request is open, it needs no separate confirmation.

A recoverable stage outcome — implementation issues, Review `CHANGES REQUIRED`, QA `FAIL` — must loop back into implementation automatically and retry. Do not pause for user confirmation before retrying.

It may stop before the merge only for a genuine blocker:

- A requirement ambiguity only the user can resolve (Review `REQUIREMENT CLARIFICATION`, or a blocking question raised during implementation).
- QA `BLOCKED` (verification itself cannot proceed).
- A per-stage retry limit is exceeded without resolving the problem (see `work-next`).
- The Merge Request cannot be merged as-is for a reason this workflow may not fix on its own:
  a draft, or a blocked merge state (branch protection, a required check that cannot pass).
  A **merge conflict is not one of these** — resolve it on the working branch per
  **Merge Conflicts**. Never force a merge past any of them.
- A live-system mutation would require explicit confirmation (see existing Keycloak / production DB rules).
- Two well-evidenced verdicts on the same Issue disagree and only the user can settle it
  (see **Dependency Resolution** → When a verdict contradicts a recent one).

Otherwise, do not halt the workflow short of a merged Merge Request and a `Done` Issue.

---

# Read-Only Stages

This is the single definition of "read-only"; `discover-issues`, `triage-backlog`, `ready-issue`, `report-bug`, `close-epic`, `close-issue`
and every agent they spawn defer to it. A second definition in `.claude/` is a bug to fix.

These stages never change the repository. They read the codebase and the Issue tracker,
form a judgment, and record that judgment in GitLab. That is their entire output.

## What must not change

While one of these stages runs, nothing in the working tree may be written — not production code,
tests, configuration, migrations, documentation, or `.claude/`. The boundary is **any file in the
repository**.

Not permitted:

- `Edit`, `Write`, `NotebookEdit`
- Shell writes into a repository path: `>`, `>>`, `sed -i`, `patch`, `tee`, `mv`, `rm`, `cp`
- Commands that write as a side effect: formatters, `--fix` linters, code generators,
  dependency installs that touch a lockfile, database migrations, builds that commit artifacts
- Any `git` command that changes state: `add`, `commit`, `checkout`, `switch`, `branch`,
  `merge`, `rebase`, `stash`, `restore`, `reset`, `push`

Read-only inspection (`cat`, `sed -n`, `grep`, `find`, `git log`/`diff`/`show`, `glab issue view`,
`scripts/issue-dependency-status.sh`) is expected. Scratch notes go to the session scratchpad, never
into the repository.

## What may change

Only GitLab Issue state, and only the mutations listed for that stage:

| Stage | Permitted GitLab mutations |
| --- | --- |
| `discover-issues` | Create new Issues in `Inbox` with `Priority` set; comment on an existing Issue when the finding is already covered by it |
| `triage-backlog` | Move `Inbox → Backlog`; set `Priority` on each Issue it moves |
| `ready-issue` | Move `Backlog → Ready`; post the Readiness Report as a comment; rewrite Epic shorthand in the Issue body to `#<number>` (required by **Dependency Resolution** → Recording dependencies) |
| `report-bug` | Create exactly one Issue directly in `status::Backlog` with `user-request`, `bug`, `priority::P0`, `hotfix` — see **How to change status** for the Inbox-skip exception this row grants |
| `close-epic` | Move an `epic` Issue `Inbox → Done` (one call: `remove_labels=status::Inbox`, `add_labels=status::Done`) and close it, only for Epics the user named in their own `/close-epic close #<n>` slash command and that re-read as CLOSABLE just before; see **Legal Transitions** for the marker-gated transition. Also: file a fix Issue (in `Inbox`, `Priority` set, no `user-request`) for each unmet non-child acceptance criterion, comment on an existing Issue that already covers it, and comment the Issue numbers on the Epic |
| `close-issue` | Move an `Inbox` / `Backlog` / `Ready` Issue (not `epic`; never `In Progress` / `Review` / `QA`) to `Done` (one call: `remove_labels=status::<current>`, `add_labels=status::Done`) and close it, only for Issues the user named in their own `/close-issue #<n> [#<n> ...]` slash command; post the reason as a comment first; see **Legal Transitions** for the marker-gated transitions |

Anything else is out of bounds — including closing an Issue the user did not name in their own `/close-epic close` or `/close-issue` command, which stays the user's call.

## When a read-only stage finds something it wants to fix

Do not fix it — even a one-line "obvious" fix is a code change. File it, or comment on the Issue
that already covers it, per **Scope Control**, and report it.

If a stage cannot judge without changing a file, report a blocker; do not make the change.

---

# Implementation Rules

Before editing code:

1. Read the relevant GitLab Issue.
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

This is the single definition of how implementation proceeds; `work-next`, `implement-issue`, the
`implementer` agent and `review-issue` defer to it. A second definition in `.claude/` is a bug to fix.

Every Issue is implemented test-first:

1. **RED — write the acceptance tests.** Translate the Issue's Acceptance Criteria into Gherkin
   scenarios, run them, and confirm every new scenario **fails**. Record the failure output.
2. **GREEN — change the production code.** Only after red has been recorded for the criterion
   being implemented.
3. **Repeat** in small cycles, one criterion (or one unit of behavior) at a time.

A new acceptance test that passes before any production code changed does not exercise the
criterion. Fix the scenario until it fails for the right reason (the behavior is missing, not a typo
in a selector or step). Never skip the red step, and never report it done without the failure output.

## Where the tests live

| Kind | Location | Runner |
| --- | --- | --- |
| Acceptance (Gherkin) | `apps/web/e2e/features/**/*.feature` — Japanese keywords (`機能:` / `シナリオ:` / `前提` / `もし` / `ならば`); step definitions in `apps/web/e2e/steps/` | `npm run test:at` (`playwright-bdd`; stage projects `at-setup` → `at-seed` → `at-provision` → `at-main` → `at-destructive`, selected by `@stage:` tags) |
| Frontend unit | `apps/web/src/**/*.test.ts(x)` | `npm run test`, `npm run test:coverage` (jest) |
| Backend unit / integration | `services/<svc>/src/test/**` | `./gradlew :services:<svc>:test` (JUnit + JaCoCo) |

Write the Gherkin scenario wherever the criterion is reachable through the product. When it
genuinely cannot be reached from the web UI (an internal service contract, a migration, an
operational behavior), say so in the implementation report, name why, and express it as a
service-level test instead.

## Never edit tests and production code in the same phase

An edit phase is either a **test phase** or a **production phase**. Never both.

- **Test phase**: only test code changes — `**/*.feature`, `apps/web/e2e/**`,
  `**/*.test.ts(x)`, `**/*.spec.ts`, `**/src/test/**`, `**/src/testFixtures/**`, and
  test-only fixtures and helpers. Production code is not touched, not even a one-character fix.
- **Production phase**: only production code changes. No test file is touched — not to adjust
  an assertion, not to fix an import, not to make something compile.

Commit each phase separately (`test: …`; `feat:` / `fix:` / `refactor:`). Verify before every commit:

```bash
git diff --cached --name-only
```

Every path in that list must be on the same side of the line. If it is not, unstage and split.

When a production change makes existing tests stop compiling, commit the production phase, then do
the mechanical test adaptation as the next test phase. The branch must be green before it is pushed —
enforced by `scripts/git-hooks/pre-push` (see **Enforcement** → Git hook), not per commit: a RED
test-phase commit is allowed, a push of a red `apps/web` branch is refused.

## Coverage

Tests must cover the production code this Issue adds or changes to at least **90% C1
(branch/decision) and 90% C2 (condition)**.

- **Scope: the code this Issue changed**, not the repository as a whole; pre-existing debt in files
  you did not touch is never an excuse for leaving new code uncovered.
- **JVM**: JaCoCo's `BRANCH` counter — `./gradlew :services:<svc>:test jacocoTestReport`,
  report at `services/<svc>/build/reports/jacoco/test/html/index.html`. The compiler
  short-circuits `&&` / `||` into separate bytecode branches, so this counter reflects
  condition coverage, not merely decision coverage.
- **Frontend**: jest's `branches` metric — `npm run test:coverage` (v8 provider), read per
  changed file, not from the global summary.
- Report the measured numbers **for the changed files** with the command that produced them.
- The repository-wide thresholds (`apps/web/jest.config.ts` → `coverageThreshold`, currently
  40) are a floor for legacy code and a separate concern. Do not lower them, and do not raise
  them as a side effect of an Issue. `scripts/git-hooks/pre-commit` checks this floor (#1040).
- **Production code no coverage runner reaches** (`apps/*/webviews/`, `infra/e2e-stubs/**`,
  `next.config.ts`) carries no numeric target; acceptance tests verify it. See **Enforcement** →
  Coverage check; phase separation and test-first still apply.
- **Neutral paths carry no numeric target** (#1380). `.claude/hooks/paths.py` →
  `NEUTRAL_PATTERNS` (`^scripts/`, `^\.claude/`, `^docs/`, …) is not production code, and no
  runner measures Python branch coverage here (only JaCoCo and jest exist). Never write
  "C1/C2 ≥ 90%" as an Acceptance Criterion for a change confined to those paths — for example,
  a fix to `scripts/check-changed-coverage.py` (#1379) is verified by its own unit tests
  (`python3 -m unittest discover -s scripts -t scripts -p 'test_*.py'`), not by a number.
  `project-planner` checks this before writing the criterion (see its Acceptance Criteria section).

If a branch cannot be reached from a test, name it and say why in the report. Do not pad the
number with tests that assert nothing.

## Never skip a test

A failing test is fixed, never silenced. Do not add `@Disabled`, `@Ignore`, `test.skip`,
`it.skip`, `xit`, `describe.skip`, `test.fixme`, a `@skip` / `@fixme` tag, a `--grep-invert`
exclusion, or a `testPathIgnorePatterns` entry to make a run green. Do not delete a failing
test, and do not weaken an assertion until it stops failing.

When a test fails, **fix the production code first**. Change the test only when the test case
itself is demonstrably inappropriate (it asserts behavior the Acceptance Criteria do not require,
or encodes an assumption this Issue deliberately changed) — and report which test and why.

**The first exception** (user's decision, #1318, 2026-09-15): scenarios tagged `@requires-gpu` are
excluded from release verification via `AT_EXCLUDE_REQUIRES_GPU=1` in `apps/web/playwright.config.ts`
(not `--grep-invert`), and the excluded list is recorded — see `docs/ACCEPTANCE_TESTING.md` → `@requires-gpu`.

**The second exception, of the same shape** (user's decision, #1401, 2026-10-05): scenarios tagged
`@requires-real-ai-cpu` (the real-AI lane against the CPU ComfyUI) are excluded from release
verification via `AT_EXCLUDE_REQUIRES_REAL_AI_CPU=1` in `apps/web/playwright.config.ts`, and the
excluded list is recorded — see `docs/ACCEPTANCE_TESTING.md` → `@requires-real-ai-cpu`. Without that
variable the scenario runs, and fails explicitly when the CPU ComfyUI is not up.

---

# Issue Provenance

This is the single definition of the `user-request` label. `plan-issue`, `discover-issues`,
and every stage that files an Issue under **Scope Control** defer to it. Do not restate it
differently anywhere else — if you find a second definition in `.claude/`, that is a bug to
fix, not a variant to follow.

`user-request` marks an Issue **whose content came from the user's own statement of what they
want** — *did I ask for this, or did Claude come up with it?* Add it when the user described the
change (a feature, a bug they hit, a pasted error, a requirement list), whichever skill created the
Issue. Do **not** add it when Claude authored the content, even though the user set the work in
motion:

| Situation | Label |
| --- | --- |
| "この機能を実装してほしい" / "―が壊れている、調べて起票して" | `user-request` |
| A pasted error message or log the user hit | `user-request` |
| `discover-issues` findings — including when the user ran the sweep | none |
| "改善点を上げられる限り上げて起票して" — the user asked, Claude found | none |
| Claude proposed a finding mid-work and the user said "起票して" | none |
| **Scope Control** discoveries during implementation / review / QA | none |

The line is **who authored the substance**. No `user-request` label means
"Claude's own, or provenance unknown" — not an assertion that the user did not ask.

## Applying it

Only with `add_labels`, never `labels=`:

```bash
glab api "projects/:id/issues/<iid>" --method PUT -f "add_labels=user-request"
```

To list them:

```bash
glab api "projects/:id/issues?per_page=100&state=all&labels=user-request" --paginate
```

## The 2026-09-09 backfill

Old Issues were labelled best-effort from transcripts; 417 of 682 could not be attributed and were
deliberately left unlabelled. Do not treat it as complete and do not re-run a guess over the remainder.
Rationale: `docs/WORKFLOW_RULE_RATIONALE.md` → 利用者由来ラベルのバックフィル.

## hotfix

`hotfix` marks an Issue as an urgent bug the user hit themselves while using the product — an
override that jumps straight to the front of the queue, ahead of even `user-request`. It exists
so a fresh, high-urgency report never has to wait behind the ordinary backlog (decided by the
user, 2026-09-26).

**Only the user adds or removes it.** Exactly like `bug`, Claude reads the label and never adds
or removes it, including to move an Issue up the queue or to strip it after `complete-issue`
closes the Issue.

**At most 3 open Issues may carry it at once.** A closed `hotfix` Issue is not counted, so
`complete-issue` never needs to touch the label just to stay under the cap.

Two independent layers enforce this — the same split `status::` uniqueness uses (see **How
"exactly one" is enforced**), because neither one alone can see everything:

| Layer | Enforces |
| --- | --- |
| `guard.py` → the hotfix-immutability check | Denies Claude adding or removing `hotfix` on an **existing** Issue: `glab api ... --method PUT` (`add_labels=`/`remove_labels=`) and `glab issue update` (`-l`/`--label`, `-u`/`--unlabel`). |
| `guard.py` → the hotfix-creation gate | Denies `glab issue create --label ...,hotfix,...` unless the read-only stage marker is `report-bug` (#1434). This is the create-time counterpart of the row above — an independent check, not an extension of it. |
| `scripts/check-issue-labels.sh` | Detects, after the fact, more than 3 open Issues carrying `hotfix` — however they got there, including a web-UI edit. |

`guard.py` never counts open `hotfix` Issues itself — that needs an API call, and it stays
network-free by design (CLAUDE.md → Enforcement). The 3-Issue cap is `check-issue-labels.sh`'s
job alone; `/report-bug` (`.claude/skills/report-bug/SKILL.md`) counts it live via the API before
filing, and stops without creating an Issue when 3 are already open. `glab issue create --label
hotfix,...` from any other caller — a different skill, or a manual invocation with no read-only
stage marker set — is denied by `guard.py`'s hotfix-creation gate (#1434).

See **Selection order** below for how `hotfix` affects which Issue is picked next.

## Selection order

**An open `hotfix` Issue is selected before anything else, including `user-request`.** Among
multiple `hotfix` Issues, the **newest Issue number wins** — the opposite tiebreak from every
key below, because the whole point of `hotfix` is to jump the queue with the freshest report
(decided by the user, 2026-09-26; see **hotfix** above for what enforces the label itself).
Below `hotfix`, **a `user-request` Issue is selected before any Issue without it.** Provenance
is the next sort key, ahead of priority (decided by the user, 2026-09-09). **A `bug` Issue comes
next**, also ahead of priority (decided by the user, 2026-09-10). `ready-issue` Select-Next Mode
and `work-next` Step 3 defer to this; it is repeated in neither.

0. **`hotfix` — open Issues carrying it first, newest Issue number first.** This key alone
   decides among `hotfix` Issues: two of them are never further ordered by provenance, kind,
   priority, or blocking count.
1. **Provenance** — `user-request` first; every other Issue after it.
2. **Kind** — `bug` first; every other Issue after it.
3. **Priority — highest first.** `P0` > `P1` > `P2` > unset. Unset always ranks last.
4. **Is blocking count — largest first**, from `dependencies/blocking`.
5. **Issue number — oldest first.**

The keys apply strictly in order, so a `user-request` `P2` **is** selected ahead of an unlabelled
`P0`, and a `bug` `P2` ahead of a non-bug `P0` of the same provenance. That is intended. Consequences:

- An unlabelled `P0` can sit behind a large `user-request` backlog, and a trivial `bug` `P2` is
  selected ahead of an urgent non-bug `P0`. **Do not silently reorder** — report it and let the user
  decide. Read the `bug` label as it stands; never add or remove it to steer the order.
- **Absence is not evidence.** Most old Issues could not be attributed, so an unlabelled Issue may be
  one the user asked for. Never argue from the absence of the label, and never add it to an old Issue
  to promote it — if the user wants one prioritized, they will say so.

Naming an Issue explicitly ("#123 を実装して") always overrides the order, as does
`queue-priority.txt` in the unattended runner.

---

# Scope Control

Do not change unrelated files.

Do not perform opportunistic refactoring unless it is required to complete the issue or the user
explicitly requests it.

If a problem outside the issue is discovered, do not silently fix it and do not wait for the user's
judgment on whether it is worth filing.

First, search for an existing Issue: `glab issue list --search "<term>"` (add `--all` for closed ones), separately for each affected file path, class/symbol name, and the observable symptom.

- If an open Issue already covers the same problem, do **not** create a new one. Add a comment to that Issue with the new evidence (where it was re-encountered, which stage found it, any detail its body lacks) and report its number instead.
- If the new finding is genuinely broader or narrower than a matching Issue, say so in the comment, and only then decide whether a separate Issue is warranted.
- Only when no existing Issue covers it, create a new one.

Create the new GitLab Issue in `Inbox` using the `project-planner` Issue template (Title, Background, Problem, Goal, Requirements, Acceptance Criteria, Scope, Out of Scope, Dependencies). Whichever stage discovers the problem files it immediately.

The Issue's `Priority` field (P0/P1/P2) must be set before the Issue is considered filed. Never leave priority unset on a newly discovered Issue.

## At most five Acceptance Criteria

This is the single definition. `plan-issue`, `discover-issues` and the `project-planner` agent
defer to it; do not restate it differently anywhere else.

**An Issue carries at most five Acceptance Criteria.** More than five means the Issue is too
big — **split it into another Issue**, do not fit it into five.

Never fit it into five by coarsening the wording or folding several checks into one bullet.

Split only where each part can be implemented, reviewed, QA'd and merged **without waiting for
its siblings**. If no such seam exists, do not split — reduce the scope instead and record what
was left out under **Out of Scope**, naming the follow-up Issue. A sequence-dependent split is
worse than a large Issue.

Why the cap is five: `docs/WORKFLOW_RULE_RATIONALE.md` → 受け入れ基準が5件までである理由.

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
- A Merge Request was opened and squash-merged into `develop`

Passing QA opens a Merge Request; `complete-issue` then merges it with `glab mr merge --squash --remove-source-branch`, moves the Issue to `Done`, and deletes the working branch locally and remotely.

Squash is this repository's merge method for Issue Merge Requests. A Merge Request that cannot be
merged cleanly is never forced through (different merge method, bypassed branch protection). A
**merge conflict** is resolved on the working branch (see **Merge Conflicts**); a draft or blocked
merge state that survives that is a blocker to report.

---

# Merge Conflicts

This is the single definition of how a conflict with `develop` is handled. `merge-request`,
`complete-issue`, `git-workflow`, and `work-next` defer to it.

A conflict between the working branch and `develop` **is resolved, not reported as a blocker.**

Resolve it on the working branch:

```bash
git fetch origin
git merge origin/develop     # resolve the conflicted files, then commit
```

then re-validate and push. This applies whenever the conflict shows up — while `merge-request`
prepares the Merge Request, or after it is open and GitLab reports `has_conflicts: true`.

What remains forbidden is getting the merge through *without* resolving it:

- Any merge method other than `--squash` — including **omitting the flag**: GitLab then merges with a
  merge commit, so `glab mr merge` without `--squash` is itself a violation.
- Marking a draft ready for review to unblock a merge
- Bypassing a protected-branch rule

GitLab has **no `--admin` equivalent**; protection lives in its protected-branch settings.
`complete-issue` merges only a clean, mergeable Merge Request: on a conflict, resolve it on the
working branch, push, re-verify, and merge — never force it.

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

This is the single definition of "dependencies are resolved"; `work-next`, `implement-issue`,
`ready-issue`, `triage-backlog`, `plan-issue` and the `project-planner` agent defer to it. A second
definition in `.claude/` is a bug to fix.

Before producing any Ready/Backlog verdict, run:

```bash
scripts/issue-dependency-status.sh <issue-number>
```

It prints the live state of every recorded dependency and any readiness verdict already posted.
Never derive a verdict from the Issue body's prose alone or carry a status over from an earlier
comment — re-read it live.

## What counts as a blocker

1. **No link and no board status blocks, by itself.** What decides readiness is whether *this*
   Issue's acceptance criteria can be implemented and verified against the codebase as it
   stands right now.
2. A parent or tracking Issue that is still open does **not** block when its children are done
   and the substance is in the code. Conversely, children being closed does not make an Issue
   ready when the substance is not actually there. Look at the code, not the board.
3. Every verdict must state which of these grounds it used, and cite the live evidence
   (the script's output, and the file paths inspected).

### Why there is no status-based blocker (changed 2026-09-03, #1024)

The rule that an open `blocked_by` link is a status-based blocker is withdrawn: GitLab CE has no
directional link, so the mechanism does not exist here. Instead **look at the code**: a verdict that
asserts "dependencies do not block" without **naming the concrete files, endpoints, or config it
inspected** is unverified; do the inspection. The script prints inputs, it does not inspect the
codebase. Rationale: `docs/WORKFLOW_RULE_RATIONALE.md` → status ベースのブロッカー.

## When a verdict contradicts a recent one

Governs whenever you are about to post a verdict opposite to a recent one, and decides *whether*
you may flip the status. Run **Reversing a verdict** below first, then apply exactly one of:

- **The earlier ground is factually falsified** — the code it cited does not say what it was
  claimed to say (e.g. a `fallback-uri` cited as covering a path that it demonstrably does not
  match). A correction, not a disagreement: flip the status and name the falsified ground.
- **Both readings are tenable on the same facts** — you weigh the same code differently rather
  than showing the earlier reading false. Do **not** flip the status. Post both readings and what
  would settle them, leave the status, and escalate to the user (a genuine blocker — see
  **Autonomous Task Execution**; #751).
- **Inspection is inconclusive** — default to `Backlog`. Ready authorizes starting without further clarification.

If you cannot tell which of the first two you are in, you are in the second.

## Reversing a verdict

Never post a contradicting verdict without running this.

1. Read the existing readiness comments (the script lists them).
2. Re-check each ground the previous verdict cited, live.
3. Say in the new comment which specific ground no longer holds, and why.

If every ground still holds, the disagreement is about the *definition* above, not the facts —
apply the definition rather than posting a contradicting verdict.

## Recording dependencies

Record dependencies as resolvable identifiers: `#<number>` in the body, optionally with a
`relates_to` link (navigation only, never itself a blocker). Epic shorthand (`A4`, `B6`, `C14`) is
not resolvable; when an Issue records dependencies only as shorthand, resolve them to numbers and
update the body before judging readiness. If they cannot be resolved, say the dependencies are
*unidentifiable* — do not assert they are *unresolved*.

---

# Enforcement

Rules that can be checked mechanically are **enforced**: a violation is refused, not reported.

## Claude Code hooks — `.claude/settings.json`

`.claude/hooks/guard.py` runs as a `PreToolUse` hook and denies the tool call outright.

| Guard | Fires on | Blocks |
| --- | --- | --- |
| Read-only stage tracking | `Skill`; `UserPromptSubmit` | Records that a read-only stage started — via the `Skill` tool, or, for a slash command (which never calls `Skill`), from a user prompt that begins with `/<read-only-skill>` (#1469); cleared by any other skill or the user's next prompt |
| Repository writes | `Write` / `Edit` / `NotebookEdit` | Any write inside the repository while a read-only stage is active |
| Mutating shell | `Bash` | `sed -i`, `rm` / `mv` / `cp` / `tee` / `patch`, state-changing `git`, dependency installs, and output redirection — while a read-only stage is active |
| Test silencing | `Write` / `Edit` | Adding `@Disabled`, `@Ignore`, `test.skip`, `it.skip`, `xit`, `test.fixme`, or `testPathIgnorePatterns` to a test or production file (`.claude/`, `docs/`, `scripts/` and `*.md` are exempt) |
| Phase separation | `Bash` (`git commit`) | A commit whose staged paths mix test code and production code |
| Hook bypass | `Bash` | `git commit` / `git merge` / `git pull` / `git push` with `--no-verify` (and `git commit -n`) — the git hook is not optional |
| Merge method | `Bash` (`glab mr merge`) | `--rebase`, and **any invocation without `--squash`** |
| Foreground subagents | `Agent` / `Task` | A call with `run_in_background: true` whose `subagent_type` is `implementer` / `reviewer` / `qa` / `project-planner` (#1269) |
| Coverage | `Bash` (`glab mr create`) | Opening a Merge Request while changed-code C1/C2 coverage is under 90% |

### SILENCERS has a single source

`.claude/hooks/silencers.py` defines the `SILENCERS` pattern table once; `guard.py` and
`scripts/git-hooks/pre-commit` both import it. Do not restate it in either hook. Rationale: `docs/WORKFLOW_RULE_RATIONALE.md` → SILENCERS を単一にした理由.

### Where squash is enforced

In two places, deliberately — the GitLab project (`squash_option: always`, `merge_method: ff`)
and the `guard.py` hook (`--squash` required on `glab mr merge`). Neither alone is enough: the
project setting is one API call away from being changed back and teaches the caller nothing,
while the hook cannot see a web-UI merge or shell indirection.

**`merge_method: ff` is what makes the history linear** — with `merge_method: merge`, GitLab creates
the squashed commit *and then a merge commit on top of it*. Rationale: `docs/WORKFLOW_RULE_RATIONALE.md` → squash.

These rules govern Issue Merge Requests into `develop`. The release merge into `main`, and the release
commit R / next-dev-version commit V pushed to `develop`, are direct pushes by
`scripts/release-verify-tag.py` (#1274, #1305), not violations; do not misread a merge commit on `main`
as one. Rationale: `docs/WORKFLOW_RULE_RATIONALE.md` → release の直接 push が squash 規則の違反ではない理由.

## Git hook — `scripts/git-hooks/pre-commit`

Bound by `bash scripts/setup-git-hooks.sh` (idempotent; step 1 of the clone procedure in `README.md`),
which sets `core.hooksPath` to `scripts/git-hooks`. That directory holds three hook scripts, and
`core.hooksPath` binds the whole directory at once, so all are always bound together — but git
invokes a **different** one depending on the route (`man githooks`), and they do not
enforce the same set of checks:

- **`pre-commit`** — invoked by `git commit`, and by the explicit commit that concludes a
  conflicted merge (CLAUDE.md → Merge Conflicts). Enforces, for **any** committer:

  1. **Phase separation** — no commit mixes test and production paths.
  2. **No test silencing** — nothing that disables a test is added to a test or production file.
  3. **Test-first** — a commit containing production code is refused while the branch has no test
     change at all. Write the failing Gherkin scenario first.
  4. **`apps/web` coverage floor** (#1040, #1377) — any commit touching `apps/web/**` runs
     `cd apps/web && npm run test:coverage` and is refused if it exits non-zero, **except** that a
     test-only commit (every staged path is test code) tolerates failing tests, so a RED test
     phase can be committed; the floor itself (jest's coverage-threshold message) is refused for
     every commit. "Green before push" is `pre-push`'s job, below. It is skipped when
     `apps/web/package.json` does not exist, is separate from `scripts/check-changed-coverage.py`
     (which gates only changed-line C1/C2), and resolves `apps/web` from the worktree the commit is
     made in (`git rev-parse --show-toplevel`), not from the hook script's own location.
  5. **Unclassified path rejection** (#1452) — a commit staging a path that
     `.claude/hooks/paths.py` classifies as none of test / production / declared-neutral is
     refused, naming the path(s). This is the pre-commit-time layer for the same invariant
     `.claude/hooks/test_paths.py` → `RepositoryExhaustiveness` checks after the fact — a script
     landing at the repository root only turned `develop` red once it merged (#1208 → #1321 →
     #1452). Not exempted on a merge commit.

- **`pre-merge-commit`** (#1452, #1460) — invoked only by a **conflict-free** `git merge`'s
  automatic commit (the most common outcome of `git merge origin/develop`, and the route
  `pre-commit` never sees). Runs **checks 2 and 5 only**, not 1, 3 or 4. `git`'s own default
  `pre-merge-commit` sample runs `pre-commit` wholesale, but this repository deliberately does
  not: check 3 (test-first) has no merge exemption, and would reject a legitimate
  `git merge origin/develop` on a branch that only touches neutral paths (`.claude/`, `docs/` —
  the most common shape of an Issue in this repository) because such a branch has no
  test-classified change of its own. Check 5 is not exempted on this route either. Check 2 uses
  a different exemption than `pre-commit`'s: `MERGE_HEAD` does not exist yet when this hook runs,
  and every added line comes from the incoming side, so "allow it if `MERGE_HEAD` has it" would
  reject nothing. It rejects an added silencer only when `HEAD:<path>` (the pre-merge side) lacks
  that pattern; editing a line whose silencer already exists on the HEAD side is allowed.
  **Check 4 is deliberately not run here** (user's decision, #1460, 2026-10-01): the
  `apps/web` coverage floor is not enforced on a conflict-free merge. The layers that still
  cover it are `pre-commit` on every ordinary commit that touches `apps/web`, and
  `npm run test:coverage` run by hand; `pre-push` (below) catches a floor broken by the merge
  at push time, and the `glab mr create` coverage guard checks changed-line C1/C2, not
  this floor. See `scripts/git-hooks/pre-merge-commit`'s own docstring for the reasons, and for
  why a rejection is not a dead end (the merge result stays in the index, so the same commit can
  fix the problem and complete the merge through `pre-commit`).

- **`pre-push`** (#1514) — invoked by `git push` (including the direct pushes of
  `scripts/release-verify-tag.py`, which only adds time when the verified tip is already green).
  This is the layer that enforces "the branch must be green before it is pushed" (Test-First
  Implementation), separately from the per-commit checks. When the pushed range changes
  `apps/web/**`, it runs `cd apps/web && npm run test:coverage` in the pushing worktree
  (`git rev-parse --show-toplevel`) and refuses the push on a non-zero exit, printing the tail of
  the output. It does **not** start `npm` for a range that leaves `apps/web` untouched, for a
  branch deletion, or when `apps/web/package.json` does not exist. A new branch's range starts at
  the merge-base with `origin/develop`; an existing branch's at the remote sha. The run checks the
  worktree's current content, which equals the pushed tip in the normal case of pushing the
  checked-out branch.

Which checks run on which route:

| Route | Hook | Checks |
| --- | --- | --- |
| `git commit` | `pre-commit` | 1–5 |
| Explicit commit concluding a **conflicted** merge | `pre-commit` | 1–5 (1 exempt; 2 exempt for patterns already on `MERGE_HEAD`, #1125) |
| **Conflict-free** `git merge` | `pre-merge-commit` | 2 (HEAD-side baseline) and 5 |
| `git push` | `pre-push` | `apps/web` green (`npm run test:coverage`) when the range touches `apps/web/**` |
| `git cherry-pick`, `git revert`, `git rebase` (no conflict) | none — git has no hook for these | none |

  Rationale for both: `docs/WORKFLOW_RULE_RATIONALE.md` → Unclassified path rejection.

**Never assert that the binding is in place — check it.** `core.hooksPath` is git configuration,
not repository content, and an unbound hook's only symptom is that nothing happens:

```bash
bash scripts/setup-git-hooks.sh --check   # non-zero when unbound
```

`scripts/test_git_hooks_binding.py` makes the same check in the unit test suite. Rationale: `docs/WORKFLOW_RULE_RATIONALE.md` → Git hook の経緯.

## Coverage check — `scripts/check-changed-coverage.py`

Computes branch coverage (C1/C2) of the production files this branch changed, from JaCoCo's
`BRANCH` counter and jest's branch map, and exits non-zero below 90%. Run it directly, or let
the Merge Request guard run it:

```bash
./gradlew :services:<svc>:test jacocoTestReport
cd apps/web && npm run test:coverage
python3 scripts/check-changed-coverage.py
```

It gates only the trees a coverage runner walks — `services/**/src`, `packages/**/src`,
`apps/*/src` (`MEASURABLE_PATTERNS` in the script); a missing report for a changed file there fails
the check. Production code outside them (`apps/*/webviews/` plain `.js`, `infra/e2e-stubs/**`,
`next.config.ts`) is **reported as unmeasurable and skipped**, and verified by the acceptance-test
layer instead. This is a **coverage** exemption only: `paths.py` still classes them as production, so
phase separation and test-first apply in full. Rationale: `docs/WORKFLOW_RULE_RATIONALE.md` → Coverage check が測定不能なコードを失敗にしない理由.

Its own unit tests: `python3 -m unittest discover -s scripts -t scripts -p 'test_*.py'`.

## What the guards are, and are not

The guards stop **mistakes**, not **circumvention**: a shell can always defeat command inspection
(`bash -c '...'`, `eval`, a variable expanding to the forbidden word). Airtight is not the goal.

They parse the command (splitting on separators, stripping env assignments and wrappers like
`timeout` / `env` / `nice` / `sudo`, respecting quotes) rather than matching a regex anchored to
its start. Rationale: `docs/WORKFLOW_RULE_RATIONALE.md` → ガード.

**`cherry-pick`, `revert` and a conflict-free `rebase` cannot be blocked**: git offers no hook on
those routes, so no git hook check runs on the commits they create (#1460). Silencers, unclassified
paths or a broken `apps/web` coverage floor brought in that way are not caught at commit time;
review and the post-hoc unit tests (`.claude/hooks/test_paths.py` → `RepositoryExhaustiveness`)
are the remaining layers. Prefer `git merge` for bringing in `develop`.

Defence against deliberate circumvention lives elsewhere:

- **GitLab protected-branch settings** — who may merge and push, enforced server-side
- **`scripts/git-hooks/pre-commit`** — runs for any committer, agent or human

### Process substitution is not indirect execution (#1035)

`>(...)` / `<(...)` is an everyday investigation command, not circumvention: `split_commands()` parses
its content as its own command and runs it through the same destructive-command check. One level is
handled; nesting is parsed as a single opaque block. A heredoc body is skipped before parsing, so `;`
and `>` inside it are not misread; the heredoc's own redirect (`cat <<EOF > out.txt`) is still caught.
Rationale: `docs/WORKFLOW_RULE_RATIONALE.md` → Process substitution と heredoc の扱い.

### Redirections, and what a read-only stage refuses

A read-only stage refuses **every** output redirection whose target is not `/dev/null` (or
`/dev/stderr` / `/dev/stdout`) — **including a write into the scratchpad**. The check looks at
the redirection, not at where it points. Compose what you need from pipes and stdout instead.

Two operators look alike and are treated oppositely, because they mean opposite things:

| Operator | Target is | In a read-only stage |
| --- | --- | --- |
| `>` `>>` `>\|` `&>` | a file name | refused unless `/dev/null` |
| `>&` | a file descriptor (`2>&1`, `1>&2`, `2>&-`) | allowed — it writes no file |

Rationale: `docs/WORKFLOW_RULE_RATIONALE.md` → ガード.

To see how a guard reads a command:

```bash
python3 .claude/hooks/guard.py explain 'timeout 60 git push --no-verify'
```

Use it before concluding that a hook "is not running" (#1029).

## When a guard blocks something

The guard is the rule speaking. Do not disable a hook, use `--no-verify`, or move a file to dodge a
path pattern. If a guard is genuinely wrong, file an Issue against it — the fix belongs in the
guard, as its own change.

---

# Learning Loop

When a failure, repeated review issue, or process problem is discovered:

1. Determine whether it is a one-time mistake or a recurring pattern.
2. If recurring, propose a rule or documentation improvement.
3. Do not silently modify project rules without explaining the reason.

The goal is to make the same category of mistake less likely.
