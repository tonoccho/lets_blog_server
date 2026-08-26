---
name: complete-issue
description: Finalize a GitHub Issue after the user confirms its Pull Request was merged — verify the merge, move the Issue to Done, and delete the working branch locally and on the remote. Use when the user says a PR was merged (e.g. "PRをマージしました", "merged #123").
---

# Complete Issue

You are finishing an Issue after a human has merged its Pull Request.

This skill never merges anything itself. Merging is exclusively the user's action, performed on GitHub. This skill only verifies that it happened and then cleans up.

Do not implement production code. Do not run `gh pr merge` under any circumstances, even if asked to "just merge it too" — redirect that request back to the user.

---

## Step 1: Identify the Issue and Pull Request

From the user's message, determine the Issue number and/or Pull Request number.

If only one was given, find the other:

- From a PR number, read the PR body/linked issues for `Closes #<n>` / `Related to #<n>`.
- From an Issue number, find its linked Pull Request via the GitHub Project's "Linked pull requests" field or `gh issue view`.

If neither is identifiable, ask the user which Issue/PR they mean. Do not guess among multiple open PRs.

---

## Step 2: Verify the merge

Check the actual state — do not trust the claim alone:

`gh pr view <pr-number> --json state,mergedAt,baseRefName,headRefName`

If `state` is not `MERGED`:

Stop. Report the actual state (e.g. still `OPEN`, or `CLOSED` without merging). Do not change the Issue status and do not delete anything.

If `state` is `MERGED`:

Continue. Record `baseRefName` (base branch) and `headRefName` (working branch).

---

## Step 3: Confirm the Issue's current status

Read the Issue's GitHub Project status.

Expected: `QA`.

If it is already `Done`, report that no change is needed, but still perform the branch cleanup in Step 5 if the branch still exists.

If it is in an unexpected state (e.g. still `In Progress`, or `Backlog`), report the discrepancy and ask before proceeding — something in the pipeline may have been skipped.

---

## Step 4: Move the Issue to Done

Change:

`QA → Done`

---

## Step 5: Clean up the branch

1. Switch to the base branch recorded in Step 2 (normally `develop`).
2. Pull the latest state: `git pull --ff-only origin <base-branch>`.
3. Delete the local working branch: `git branch -d <head-branch>`.
   - If `-d` refuses because the branch isn't detected as fully merged locally (stale local history), fetch first (`git fetch origin`) and retry. Do not use `-D` to force-delete without understanding why `-d` refused.
4. Delete the remote working branch, if it still exists: `git push origin --delete <head-branch>`.
   - GitHub often auto-deletes the head branch on merge; if the remote branch is already gone, skip this without error.

Never delete a branch before Step 2 has confirmed the PR is actually merged.

---

## Step 6: Report

Return:

## Completed Issue

Issue number and title.

## Pull Request

Number, URL, merge confirmation (`mergedAt`).

## Status Change

`QA → Done`

## Branch Cleanup

- Local branch: deleted / already gone
- Remote branch: deleted / already gone (auto-deleted on merge)

## Working Directory

Current branch after cleanup (should be the base branch, up to date).

---

## Rules

Never run `gh pr merge` or any command that merges a Pull Request.

Never delete a branch without first confirming via `gh pr view` that its PR is actually merged.

Never move an Issue to `Done` based only on the user's claim — always verify against GitHub first.

If verification is inconclusive (e.g. `gh` command fails, ambiguous PR), stop and report rather than guessing.
