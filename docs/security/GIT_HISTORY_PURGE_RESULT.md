# Git History Purge — Result

**Executed:** 2026-08-30 · **Tool:** `git-filter-repo` 2.47.0
**Repository:** `https://github.com/sumansinghs71/agent`

> No secret value appears in this document. Secrets are referenced as `S1`, `S2`, `S3`.

---

## Outcome in one line

**The five authorized branches are clean. Two branches and seven pull-request refs are not, and
neither can be fixed by the authorization I was given.** Details in §5 — this needs a decision.

---

## 1. What was purged

| Label | Kind | Status before | Status after |
|---|---|---|---|
| S1 | Azure OpenAI API key | in history + at `HEAD` | replaced with `${AZURE_OPENAI_API_KEY}` |
| S2 | MySQL `root` password | in history + at `HEAD` | replaced with `${BS2_DB_PASSWORD}` |
| **S3** | **Azure Search key** | **in history — not previously catalogued** | replaced with `${AZURE_SEARCH_API_KEY}` |

**S3 was found during this operation, not by the audit and not by gitleaks.** The replacement table
was built by walking *every revision* of the two configuration files rather than by listing the
secrets already known, and that sweep surfaced a third credential that had been parameterised at
some later commit but remained a literal in earlier ones. It should be rotated if it has not been.

This is the second time in this project that enumerating from known findings would have missed
something — see `SECRET_SCAN_REPORT.md` §5 for the first.

---

## 2. Backups (local only, never pushed)

| Backup | Path | Verification |
|---|---|---|
| Mirror of pre-purge remote | `~/backups/agent-pre-purge.git` | 14 refs captured |
| Bundle of pre-purge local | `~/backups/agent-pre-purge.bundle` | `git bundle verify` → *"The bundle records a complete history"* |

Both retain S1/S2/S3 by design. They are the rollback path. **Do not push them.**

---

## 3. Commands

```bash
# backup
git clone --mirror https://github.com/sumansinghs71/agent.git ~/backups/agent-pre-purge.git
git bundle create ~/backups/agent-pre-purge.bundle --all
git bundle verify ~/backups/agent-pre-purge.bundle

# materialize every remote branch so none is left behind by the rewrite
git fetch origin '+refs/heads/*:refs/remotes/origin/*' --prune
git branch --track <each remote branch>

# rewrite (replacement table lives OUTSIDE the repo, chmod 600, never committed, never echoed)
git filter-repo --replace-text ~/agent-purge-replacements.txt --force

# push (explicit refspecs, one branch at a time)
git push --force origin "refs/heads/${b}:refs/heads/${b}"
```

Result: **36 commits in, 36 commits out.** No history was dropped; only literal values changed.

---

## 4. Verification gates — all passed before any push

| # | Gate | Result |
|---|---|---|
| 1 | S1 absent from every rewritten ref | ✅ 0 commits |
| 2 | S2 absent from every rewritten ref | ✅ 0 commits |
| 3 | Full-history `gitleaks` on rewritten local repo | ✅ no leaks found |
| 4 | Branch count as expected | ✅ 7 |
| 5 | Commit-subject continuity on `DAG` | ✅ identical modulo the new M0 commit |
| 6 | Tags accounted for | ✅ none exist, none expected |
| 7 | No secret literal in any reachable object | ✅ 0 of 267 blobs |
| 8 | M0 commit present in rewritten history | ✅ |
| 9 | Repository still builds | ✅ `BUILD SUCCESS` |
| 10 | Tests still pass | ✅ 113/113 |

## 4.1 Branches rewritten and pushed

| Branch | Before | After |
|---|---|---|
| `main` | `35ca826` | `ac1611b` |
| `DAG` | `9d5c4e8` | `a0a0b7c` |
| `development` | `a332192` | `c868984` |
| `release` | `35ca826` | `ac1611b` |
| `Intent-Classification` | `55ee3db` | `7dc3f33` |

> **Note.** A later, unrelated rewrite removed AI-assistant attribution trailers from twelve commit
> messages, which changed the SHAs on `main` and `DAG` again. The values above are the ones this
> operation produced and are left as recorded; `a0a0b7c` is therefore no longer reachable. The
> table documents what this purge did, not the repository's current tips.

## 4.2 Fresh-clone verification (post-push)

```bash
git clone https://github.com/sumansinghs71/agent.git ~/work/agent-verify
```

| Check | Result |
|---|---|
| S1/S2 on each of the 5 authorized branches | ✅ 0 / 0 on all five |
| `gitleaks` restricted to the 5 authorized branches | ✅ **20 commits scanned, no leaks found** |
| Secret in the working tree | ✅ none |
| Build + tests from the fresh clone | ✅ **113 run, 0 failures, 0 errors** |
| `gitleaks` across **all** refs | ❌ **2 leaks** — see §5 |

---

## Current status update — 2026-08-31

The two ordinary remote branches described in §5.1 were subsequently reviewed,
backed up locally, and deleted after authorization.

A fresh-clone scan of all ordinary remote heads now reports no S1/S2/S3 leaks.

The GitHub-managed `refs/pull/*` references described in §5.2 remain the only
known repository-hosted stale references containing historical secret-bearing
objects.

The sections below are preserved unchanged as the historical record of the
2026-08-30 purge operation.

## 5. What is NOT cleaned, and why

### 5.1 Two branches outside the authorization (BLOCKING — needs a decision)

The mirror backup revealed two branches on the remote that the purge plan never listed, because they
were not present when that plan was written:

- `claude/analyze-repository-deep-011CUhgAkfU1Gn4TZQrbMTjv`
- `claude/daily-coding-java-questions-011CUkAi7qpTnF2LWvahQVs7`

**Both contain S1 and S2, in history and at their tips.** The approved scope named five branches;
force-pushing a branch that was never mentioned is a destructive act outside that scope, so they
were left alone.

Consequence: **a fresh `git clone` still fetches these branches, so a full-history scan still
reports leaks.** The rewritten, clean versions already exist locally (`41d952c` and `5060c6a`) and
passed the same gates, so completing this is one push once authorized.

### 5.2 Seven pull-request refs (cannot be fixed from this side, at all)

| Ref | S1 commits | S2 commits |
|---|---|---|
| `refs/pull/1/head` | 1 | 2 |
| `refs/pull/2/head` | 1 | 2 |
| `refs/pull/3/head` | 1 | 2 |
| `refs/pull/4/head` | 2 | 3 |
| `refs/pull/4/merge` | 2 | 3 |
| `refs/pull/5/head` | 2 | 3 |
| `refs/pull/6/head` | 3 | 4 |

`refs/pull/*` is **read-only and GitHub-managed**. No force-push, branch deletion, or history
rewrite performed by a repository owner can alter it. These refs are not fetched by a default
`git clone`, but they are reachable to anyone who requests them explicitly and are visible in the
PR UI.

**Only GitHub Support can remove them.** Request: ask Support to garbage-collect unreachable objects
and stale pull-request refs on `sumansinghs71/agent`, citing the completed history rewrite. Until
that is done, S1/S2 remain retrievable from this repository by anyone who knows to look.

### 5.3 Forks and existing clones

Unchanged and unchangeable. Any fork made before today still contains all three secrets in full.

---

## 6. The honest summary

**Rotation removed the security risk. The rewrite cleaned the repository history.**

Those are different things and it matters not to conflate them. The values were public on a public
repository; they must be assumed harvested by the bots that watch public pushes in real time. What
today's work achieved is that a reviewer cloning this repository, or a scanner running against its
default branches, no longer finds live-looking credentials. That is a presentation and hygiene
outcome, and a worthwhile one for portfolio material — but it is not what closed the exposure.

It is also, as §5 shows, **incomplete**: two branches and seven PR refs still carry the values. A
report claiming "history purged" without those caveats would be false.
