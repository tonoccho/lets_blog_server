---
name: qa-issue
description: Validate a reviewed GitLab Issue from the user's perspective. Verify acceptance criteria, user-visible behavior, edge cases, errors, and regressions. Use this skill when an Issue is in QA.
model: sonnet
---

# QA Issue

You are starting the QA workflow.

Only test Issues in the `QA` state.

Delegate behavioral validation to the `qa` agent.

The QA agent must not modify production code.

---

## Step 1: Select the Issue

If the user specified an Issue:

Use that Issue.

Otherwise find Issues with:

`Status = QA`

Prefer:

1. Highest priority
2. Oldest QA Issue

---

## Step 2: Read the requirements

Read the complete GitLab Issue.

Extract every acceptance criterion.

Each criterion must become an independently verifiable test.

Do not collapse multiple criteria into one vague test.

---

## Step 2a: Check container build freshness (before hands-on verification)

**A "healthy" container status is not evidence that it is running the branch's latest code.**
`docker compose up -d` does not recreate a container whose image was already built — a running
container keeps the image it started with even if the source under its `build:` context has
since changed. #1102's QA is the concrete case this caught too late: `lbs-media` /
`lbs-gateway` were reported healthy while running images built ~51 minutes before the branch's
last two commits, and only a manual check before verification caught it.

Run this before any hands-on/manual verification against a running container (Step 3), for
every service whose `build:` context overlaps files this Issue changed (check
`docker-compose.yml` for the service's `build.context` / `dockerfile`, e.g. `media` →
`services/media/`, `gateway` → `services/gateway/`):

```bash
# Image build time of the running container
docker inspect --format '{{.Created}}' <container-name>   # e.g. lbs-media, lbs-gateway

# Last commit time on this branch for the paths that feed that service's image
git log -1 --format=%cI -- <relevant-path>                # e.g. services/media services/gateway
```

**Decision rule:** if the image's `Created` timestamp is **older than** the last commit
timestamp for the relevant paths, the running container is stale — rebuild and recreate only
the affected services before proceeding:

```bash
docker compose build --no-deps <service...>
docker compose up -d --no-deps --force-recreate <service...>
```

If the image is already newer than (or equal to) the last relevant commit, no rebuild is
needed — do not rebuild unconditionally on every QA run; that costs minutes even when nothing
changed. Record which check was performed (image time, commit time, and whether a rebuild was
triggered) in the QA report (Step 6).

This check applies to any container QA verifies behavior against, not only `media`/`gateway` —
those are simply the services #1102 exposed the gap on.

---

## Step 3: Invoke QA agent

Ask the `qa` agent to verify:

- Happy path
- Edge cases
- Error handling
- Regression behavior
- Acceptance criteria
- User-visible behavior

The QA agent must distinguish:

`Executed`

from:

`Code inspected`

from:

`Not verified`

---

## Step 4: Evaluate result

### PASS

All required acceptance criteria are verified.

No significant regression is found.

Proceed to Merge Request creation (Step 5a) — do not move straight to Done. The merge is `complete-issue`'s job, not this skill's.

### FAIL

A required behavior does not work.

Return to implementation.

### BLOCKED

The environment prevents meaningful validation.

Keep the Issue in QA and report exactly what is missing.

---

## Step 5: Update status

If FAIL:

`QA → In Progress`

If BLOCKED:

remain:

`QA`

If PASS: do not change status yet — continue to Step 5a first.

---

## Step 5a: Create the Merge Request (PASS only)

QA passing means the work is behaviorally correct, not that it is done — nothing has been merged yet.

Invoke the `merge-request` skill to open (or confirm an existing) Merge Request for the Issue's branch.

The Issue's `status::` label remains:

`QA`

Do not move it to `Done` here. `Done` is reserved for after the Merge Request is actually merged — see the `complete-issue` skill.

Report the Merge Request URL to the user. Then stop this workflow and return to the caller — `work-next` invokes `complete-issue`, which merges the Merge Request and finalizes the Issue. Never merge from this skill.

---

## Step 6: Record evidence

Add a concise QA report to the Issue or PR.

Include:

- Test scenarios
- Commands
- Results
- Acceptance criteria
- Known limitations
- Container build freshness check (Step 2a): the containers checked, whether a rebuild was
  needed, and the evidence (image `Created` time vs. last relevant commit time)

---

## Output

### QA Result

PASS / FAIL / BLOCKED

### Scenarios

List tested scenarios.

### Acceptance Criteria

PASS / FAIL / NOT VERIFIED

### Unrelated Issues Filed

New Issue numbers created in `Inbox` for unrelated problems noticed during QA. If none:

`None`

### Evidence

Explain how the result was established.

### Status

Current `status::` label.

### Next Step

`Awaiting complete-issue (merge)` (PASS) / `Implementation` (FAIL) / `Blocked`

If PASS, include the Merge Request URL from Step 5a and state that the Issue moves to `Done` via `complete-issue`, which performs the squash merge.

---

## Rules

Never treat a container's "healthy" status as evidence that it runs current code — verify build
freshness per Step 2a before any hands-on verification against it.

Never claim UI or end-to-end behavior was verified unless it was actually tested.

Never ignore failed acceptance criteria.

Never modify production code.

Do not mark Done merely because automated tests pass.

Never merge a Merge Request from this skill — merging belongs to `complete-issue`.

Never move an Issue to `Done` from this skill — `Done` requires a confirmed merge, handled by `complete-issue`.

The user's expected behavior is the final authority.
