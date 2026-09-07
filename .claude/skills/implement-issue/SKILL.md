---
name: implement-issue
description: Implement a GitLab Issue that has been explicitly marked Ready. Use this skill to analyze the repository, create an implementation plan, implement the change, and validate it.
model: sonnet
---

# Implement Issue

You are starting the implementation workflow.

Only implement Issues that are explicitly in the `Ready` state.

Delegate implementation work to the `implementer` agent.

---

## Step 1: Select the Issue

If the user specified an Issue number:

Use that Issue.

If no Issue number was specified:

Search GitLab for Issues with:

`Status = Ready`

Do not select:

- Inbox
- Backlog
- In Progress
- Review
- Done

If multiple Ready Issues exist:

Select according to:

1. Highest priority
2. Dependency readiness
3. Oldest Ready Issue

Before implementing, report which Issue was selected.

---

## Step 2: Verify readiness

Read the complete Issue, **including its existing comments**.

Verify:

- Goal
- Requirements
- Acceptance Criteria
- Scope
- Out of Scope
- Dependencies — per **Dependency Resolution** in `CLAUDE.md`. Run
  `scripts/issue-dependency-status.sh <issue-number>` and judge from its live output, not from
  the Issue body's prose or from what an earlier comment said a dependency's status was.

If the Issue is no longer implementation-ready:

Stop.

Return the Issue to Backlog and explain why, citing the live evidence you checked.

Before posting a rollback that contradicts a recent Ready promotion, apply the same guard as
`work-next` Step 4: re-check each ground the promotion cited and name the one that no longer
holds. If they all still hold, apply the `CLAUDE.md` definition instead of posting a
contradicting verdict (#751).

Do not guess missing requirements.

---

## Step 3: Update status

Change:

`Ready → In Progress`

Record the Issue number being worked on.

---

## Step 4: Create the working branch

Invoke the `git-workflow` skill to:

1. Inspect repository state (stop if there are unrelated uncommitted changes).
2. Determine and update the base branch (this project branches from `develop`, not `main`).
3. Create the Issue branch (`<type>/<issue-number>-<short-description>`).

Do not invoke the `implementer` agent until the correct branch is checked out and confirmed.

---

## Step 5: Invoke implementer

Ask the `implementer` agent to work **test-first**, per `CLAUDE.md` →
**Test-First Implementation**. That is the single definition of the order; do not restate a
different one here.

1. Read the Issue.
2. Inspect the codebase.
3. Analyze impact.
4. Create an implementation plan, including which Acceptance Criterion becomes which Gherkin
   scenario.
5. **RED** — write the acceptance tests: Gherkin scenarios in
   `apps/web/e2e/features/**/*.feature` (Japanese keywords) with step definitions in
   `apps/web/e2e/steps/`, plus the unit tests for the behavior being added. Run them
   (`cd apps/web && npm run test:at:fast`, `npm run test`, `./gradlew :services:<svc>:test`)
   and **capture the failure output**. A new scenario that passes here does not exercise its
   criterion — fix the scenario before continuing.
6. Commit the test phase alone (`test: …`).
7. **GREEN** — implement the plan, touching no test file in that phase.
8. Commit the production phase alone (`feat:` / `fix:` / `refactor:`).
9. Repeat 5–8 in small cycles until every criterion is covered.
10. Run full validation and measure C1/C2 coverage of the changed production code.
11. Report the final result, including the red evidence and the coverage numbers.

A test phase and a production phase never merge into one edit. When a production change breaks
test compilation, finish the production phase, then adapt the tests as the next test phase.

---

## Step 6: Validate implementation result

Check the implementer's report.

Verify that:

- Tests were actually run.
- Failed tests are not hidden.
- Acceptance criteria were checked.
- No unrelated scope expansion occurred.
- The working tree is understood.

Also verify the test-first evidence — this is a gate, not a formality:

- **Red evidence exists** for every Acceptance Criterion implemented: the failing scenario
  names and the command that produced the failure. A criterion implemented without a prior
  failing test is a failed stage; send it back.
- **Phase separation holds.** Check it against the history, not the report:

  ```bash
  git log --oneline --name-only origin/develop..HEAD
  ```

  No commit may contain both test paths (`**/*.feature`, `apps/web/e2e/**`, `**/*.test.ts(x)`,
  `**/*.spec.ts`, `**/src/test/**`, `**/src/testFixtures/**`) and production paths.
- **Coverage** of the production code this Issue changed is at least 90% C1 and 90% C2, with
  the measured numbers and the command that produced them (JaCoCo `BRANCH` for JVM, jest
  `branches` for the frontend). A report without numbers is not verified.
- **No test was skipped, ignored, deleted, or weakened** to make a run green
  (`@Disabled`, `@Ignore`, `test.skip`, `it.skip`, `xit`, `test.fixme`, `--grep-invert`,
  `testPathIgnorePatterns`). Check the diff for these directly.

Do not trust a statement such as "all tests pass" without evidence from the tool output.

---

## Step 7: Commit and push

Invoke the `git-workflow` skill to:

1. Inspect the diff and confirm every change belongs to this Issue.
2. Run project validation (formatter, lint, typecheck, tests) if not already confirmed in Step 6.
3. Commit each phase separately, with a message following the repository's convention. Before
   each commit, confirm `git diff --cached --name-only` lists only test paths or only
   production paths — never both.
4. Push the branch (`git push -u origin <branch-name>`).

Do not skip this step — implementation is not usable by later stages (Review, QA, Merge Request) until it is committed and pushed.

---

## Step 8: Move to Review

If implementation is complete and validation is successful:

Change:

`In Progress → Review`

Do not move directly to Done.

If implementation is blocked:

Keep:

`In Progress`

and report the blocker.

---

## Output

Return:

### Issue

Issue number and title.

### Status

Current `status::` label.

### Implementation Summary

Short summary.

### Tests

Commands executed and results, including:

- The **red evidence**: which scenarios failed before implementation, and the command run.
- The **C1/C2 coverage** of the production code this Issue changed, with the command run.
- Any criterion covered by a service-level test instead of Gherkin, and why the web UI could
  not reach it.

### Acceptance Criteria

PASS / FAIL / NOT VERIFIED.

### Unrelated Issues Filed

Any new Issue numbers created in `Inbox` for unrelated problems discovered during implementation. If none:

`None`

### Next Step

Usually:

`Review`

---

## Rules

Never implement an Issue that is not Ready.

Never mark an Issue Done.

Never silently change requirements.

Never change production code before a failing test exists for the behavior it implements.

Never edit test code and production code in the same phase or the same commit.

Never make a run green by skipping, ignoring, deleting, or weakening a test. When a test fails,
fix the production code first; change the test only when the test case itself is demonstrably
wrong, and say so in the report.

Never fix unrelated problems discovered during implementation.

If unrelated problems are discovered, immediately create a separate Issue in `Inbox` for each (using the `plan-issue` template) — do not ask the user for judgment on whether to file it. Set each Issue's `Priority` field (P0/P1/P2) before considering it filed; never leave priority unset. Report the new Issue number(s) in the final output.