# ADR 0003 — Rotate and disclose; treat history rewriting as hygiene, not remediation

**Status:** Accepted (rewrite pending owner approval) · **Date:** 2026-08-30 · **Milestone:** M0.1–M0.3

## Context

Two credentials — an Azure OpenAI API key and a MySQL `root` password — were committed in the
initial commit and were still present at `HEAD`, despite two commits titled *"removed hardcoded
credentials"*. The repository is public and intended as portfolio material.

## Decision

1. **Rotation is the remediation.** The values were public; they must be assumed harvested by the
   bots that watch public pushes. Rotation is owner-managed and outside git.
2. **Remove from the tree and parameterise.** All provider configuration became `${ENV_VAR}`
   references with a committed `.env.example`. `datasource.properties` is read as a raw
   `Properties` resource, so Spring's placeholder resolution never ran on it — which is precisely
   how a literal password ended up committed there. An explicit resolver was added.
3. **Gate the tree in CI, scan history informationally.** The working tree must be clean on every
   push. The history scan runs separately and is expected to fail until the purge is executed.
4. **Disclose in `SECURITY.md`.** Anyone who forked before the cleanup still holds the values; a
   silent scrub would misrepresent that.
5. **Prepare the rewrite; do not execute it.** Force-pushing rewritten history is destructive and
   irreversible for collaborators, so it needs explicit approval.

## Consequences

- **`gitleaks`' default rules found the API key but not the database password** — short, low-entropy,
  human-chosen credentials in `.properties` files do not trip entropy rules. That one was found only
  by manual review. A custom rule was added, with a positive and a negative control proving it fires.
  The general lesson is recorded: a clean scanner run is necessary, not sufficient.
- If the rewrite is approved, every commit SHA changes across all five remote branches, existing
  clones break, and forks stay dirty regardless. GitHub also keeps old commits reachable by SHA until
  asked to garbage-collect them — a step that is easy to miss and without which the purge is
  incomplete on the surface that matters.
- Declining the rewrite is also defensible: a documented, correctly-handled incident can read better
  than a scrubbed history.
