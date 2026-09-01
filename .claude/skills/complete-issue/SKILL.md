---
name: complete-issue
description: Merge a Pull Request that has passed QA (squash merge, head branch deleted), then move its Issue to Done and clean up the local branch. Use when the user asks to merge or finish an Issue/PR (e.g. "#123をマージして", "PRをマージしました"), and as the final stage of `work-next` after `pull-request`.
model: sonnet
---

# Complete Issue

You are finishing an Issue: merging its Pull Request, then moving the Issue to `Done` and cleaning up the working branch.

This skill performs the merge itself, with `gh pr merge --squash --delete-branch`. If a human already merged the Pull Request, skip the merge and carry on with the finalization.

Do not implement production code. Never merge a Pull Request that has not passed internal
Review and QA, and never *force* a merge past a conflicted, draft, or blocked state.

A **merge conflict** is resolved rather than reported: bring `develop` into the working branch,
fix the conflict, re-validate, push, then merge cleanly (see `CLAUDE.md` → **Merge Conflicts**).
A draft or a blocked merge state that survives that is a blocker — stop and report it.

---

## Step 1: Identify the Issue and Pull Request

From the user's message, determine the Issue number and/or Pull Request number.

If only one was given, find the other:

- From a PR number, read the PR body/linked issues for `Closes #<n>` / `Related to #<n>`.
- From an Issue number, find its linked Pull Request via the GitHub Project's "Linked pull requests" field or `gh issue view`.

If neither is identifiable, ask the user which Issue/PR they mean. Do not guess among multiple open PRs.

---

## Step 2: Confirm the Issue is cleared to merge

Read the Issue's GitHub Project status. It gates the merge, so read it *before* merging anything.

Expected: `QA` — that status is what proves internal Review and QA both passed.

- `QA` → proceed to Step 3.
- Already `Done` → the Issue was finalized before. Do not merge anything. Check the PR state in Step 3 and, if a stale branch is still around, perform only the cleanup in Step 5.
- Anything else (`In Progress`, `Review`, `Backlog`, …) → stop and report the discrepancy. Do not merge — something in the pipeline was skipped.

---

## Step 3: Merge the Pull Request

Read the actual state — never act on the user's claim alone:

`gh pr view <pr-number> --json state,isDraft,mergeable,mergeStateStatus,mergedAt,baseRefName,headRefName`

Record `baseRefName` (base branch) and `headRefName` (working branch), then branch on `state`:

`MERGED` — already merged by a human. Skip the merge and continue at Step 4.

`CLOSED` — closed without merging. Stop. Do not reopen it, do not merge, do not delete anything. Report the state.

`OPEN` — merge it, but only if all of the following hold:

- Step 2 found the Issue in `QA`.
- `isDraft` is `false`.
- `mergeable` is `MERGEABLE`, and `mergeStateStatus` is neither `DIRTY` nor `BLOCKED`.

If `mergeable` is `CONFLICTING` or `mergeStateStatus` is `DIRTY`, **resolve the conflict** —
this one is not a stop. Per `CLAUDE.md` → **Merge Conflicts**:

```bash
git switch <headRefName>
git fetch origin
git merge origin/develop     # resolve, then commit
```

Re-run the full relevant validation, push, and re-read `gh pr view` before merging. If
validation fails after the resolution, fix the production code first; change a test only when
the test case itself is demonstrably inappropriate, and never by skipping or deleting it.

If any **other** precondition fails (the Issue is not in `QA`, the PR is a draft,
`mergeStateStatus` is `BLOCKED`), stop and report which one. Do not mark a draft ready for
review, and do not bypass a branch protection rule to get the merge through.

Otherwise merge:

`gh pr merge <pr-number> --squash --delete-branch`

Squash is this repository's required merge method, and the head branch is always deleted on merge. Do not substitute `--merge` or `--rebase`, and never add `--admin`.

Then re-read `gh pr view <pr-number> --json state,mergedAt` and confirm `state` is `MERGED`. If the merge command fails, or the state is anything else, stop and report the failure — do not retry with different flags.

---

## Step 4: Move the Issue to Done

Change:

`QA → Done`

Only after Step 3 has confirmed `state` is `MERGED`.

---

## Step 5: Clean up the branch

`--delete-branch` already removed the remote head branch. The local branch remains.

1. Switch to the base branch recorded in Step 3 (normally `develop`).
2. Pull the latest state: `git pull --ff-only origin <base-branch>`.
3. Delete the local working branch: `git branch -d <head-branch>`.
   - A squash merge rewrites the commits, so `-d` will normally refuse with "not fully merged" even though the work is in the base branch. That refusal is expected here, not a warning sign: once Step 3 confirmed `MERGED` and the squash commit is present on the base branch, use `git branch -D <head-branch>`.
4. Delete the remote working branch only if it somehow still exists: `git push origin --delete <head-branch>`. `--delete-branch` normally handled it already — skip without error.

Never delete a branch before Step 3 has confirmed the PR is actually merged.

---

## Step 6: Report

Return:

## Completed Issue

Issue number and title.

## Pull Request

Number, URL, merge confirmation (`mergedAt`), and whether this skill merged it (squash) or it was already merged by a human.

## Status Change

`QA → Done`

## Branch Cleanup

- Local branch: deleted / already gone
- Remote branch: deleted / already gone (deleted by `--delete-branch`)

## Working Directory

Current branch after cleanup (should be the base branch, up to date).

---

## Rules

Only ever merge with `gh pr merge --squash --delete-branch`. Never `--merge`, `--rebase`, or `--admin`.

Never merge a Pull Request whose Issue is not in `QA` — that status is the proof that Review and QA both passed.

Never delete a branch without first confirming via `gh pr view` that its PR is actually merged.

Never move an Issue to `Done` based only on the user's claim — always verify against GitHub first.

If verification is inconclusive (e.g. `gh` command fails, ambiguous PR), stop and report rather than guessing.
