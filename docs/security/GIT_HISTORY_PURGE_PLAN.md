# Git History Purge Plan

**Status: PREPARED — NOT EXECUTED. Awaiting explicit owner approval.**

**Repository:** `sumansinghs71/agent` → `https://github.com/sumansinghs71/agent.git`
**Date prepared:** 2026-08-30 · **Milestone:** M0.3

> No secret value appears in this document. Secrets are referenced as `S1` and `S2`, defined in
> [SECRET_SCAN_REPORT.md](SECRET_SCAN_REPORT.md).

---

## 0. Read this first — rotation is the real control

**Rewriting history does not un-leak anything.** S1 and S2 have been present in a public repository.
They must be assumed to have been cloned, forked, mirrored by third-party crawlers, cached by GitHub's
own network view, and indexed by automated secret-harvesting bots that watch public pushes in real time.

Therefore:

| Control | Effect | Status |
|---|---|---|
| **Rotate / revoke S1 and S2 at the provider** | **This is the only control that actually removes the risk.** | Owner-managed, outside git. Owner has confirmed this is done or in progress. |
| Remove from working tree | Stops future propagation | ✅ Done in M0.1 |
| Secret scanning in CI | Stops recurrence | ✅ Done in M0.11 |
| Rewrite history | Cosmetic + hygiene. Removes the values from *this* repo's objects so a casual reader or a future scanner does not find them. | ⏸ This plan |

**If S1 and S2 have been rotated, the history rewrite is optional.** Its remaining value is that a
public portfolio repository should not ship a commit history in which a reviewer can trivially find a
live-looking API key — that is itself a negative signal to the audience this repository is aimed at.
That is a reputational argument, not a security one, and it is worth being clear about which is which.

---

## 1. What must be purged

| Secret | File | Commits |
|---|---|---|
| S1 (Azure OpenAI API key) | `src/main/resources/application.yml` | `e85b57f`, `55ee3db`, `63c83b7` |
| S2 (MySQL root password) | `src/main/resources/static/datasource.properties` | `e85b57f`, `b5f2e34`, `55ee3db`, `63c83b7` |

Scope: **23 commits**, 5 remote refs (`main`, `DAG`, `development`, `Intent-Classification`, `release`).

Approach: **replace the secret literals in place**, rather than deleting the files. The files are
still needed at every commit; only the values must go. `git filter-repo --replace-text` does exactly this.

---

## 2. What `git filter-repo` does

`git-filter-repo` is the tool the Git project itself recommends in place of `filter-branch`
(which is slow and has footguns) and BFG.

For this job it walks every commit in every ref, streams each blob through a replacement table, and
writes a **new** commit graph with the secret bytes substituted. Because a commit's SHA is a hash of
its content and its parents, **every commit from the earliest rewritten one onward gets a new SHA**.
The old objects remain locally until garbage-collected, and remain on the remote until force-pushed over.

`--replace-text` takes a file of `literal==>replacement` lines and never echoes the literals to the
terminal or into the rewritten history.

---

## 3. Procedure

### Step 0 — Preconditions

```bash
# Confirm S1/S2 are already rotated at the provider. Do not proceed otherwise.
# Commit or stash all M0 working-tree changes first - filter-repo refuses to run on a dirty tree.
git status --porcelain
```

Install the tool:

```bash
brew install git-filter-repo
```

### Step 1 — Back up (local, and a remote safety ref)

```bash
# Full mirror clone kept outside the working repo
git clone --mirror https://github.com/sumansinghs71/agent.git ~/backups/agent-pre-purge.git

# Local tag on every branch tip, so nothing is unreachable if the rewrite is abandoned
git tag pre-purge-DAG DAG
git tag pre-purge-main main
git tag pre-purge-development origin/development
git tag pre-purge-release origin/release
git tag pre-purge-intent origin/Intent-Classification

# Bundle - a single restorable file containing every ref
git bundle create ~/backups/agent-pre-purge.bundle --all
```

Verify the bundle before continuing:

```bash
git bundle verify ~/backups/agent-pre-purge.bundle
```

### Step 2 — Build the replacement table (never committed)

Create the file **outside the repository** so it can never be staged:

```bash
cat > ~/agent-purge-replacements.txt <<'REPL'
<S1-literal-value>==>${AZURE_OPENAI_API_KEY}
<S2-literal-value>==>${BS2_DB_PASSWORD}
REPL
chmod 600 ~/agent-purge-replacements.txt
```

The two literal values are recoverable from `git show e85b57f:src/main/resources/application.yml`
and `git show e85b57f:src/main/resources/static/datasource.properties`. **They are deliberately not
written here.**

### Step 3 — Rewrite

`filter-repo` insists on a fresh clone by default, which is the safe path:

```bash
git clone https://github.com/sumansinghs71/agent.git ~/work/agent-rewrite
cd ~/work/agent-rewrite
git filter-repo --replace-text ~/agent-purge-replacements.txt
```

`filter-repo` intentionally drops the `origin` remote after rewriting, to stop an accidental push.

### Step 4 — Verify BEFORE pushing

All four checks must pass:

```bash
# 1. No occurrence of either literal in any object, any ref
git log --all -S'<S1>' --oneline    # expect: empty
git log --all -S'<S2>' --oneline    # expect: empty

# 2. Full-history scanner run
gitleaks detect --source . --config .gitleaks.toml --redact
# expect: "no leaks found"

# 3. Replacement actually landed (placeholder present where the secret was)
git show $(git rev-list --max-parents=0 HEAD):src/main/resources/application.yml | grep -c 'AZURE_OPENAI_API_KEY'

# 4. History is otherwise intact
git rev-list --all --count     # expect: 23
git log --oneline | tail -3    # expect: same commit subjects as before
```

### Step 5 — Force-push (DESTRUCTIVE — requires approval)

```bash
cd ~/work/agent-rewrite
git remote add origin https://github.com/sumansinghs71/agent.git
git push --force --all origin
git push --force --tags origin
```

### Step 6 — After the push

1. Re-clone fresh locally; **delete every old working copy** — an old clone still holds the old objects
   and can re-push them.
2. On GitHub: Settings → check for open PRs (they will show as heavily modified or broken).
3. Ask GitHub Support to garbage-collect stale refs if the old commits remain reachable via
   `https://github.com/sumansinghs71/agent/commit/<old-sha>` — **GitHub keeps unreferenced commits
   accessible by direct SHA indefinitely until asked to clear them.** This step is easy to forget and
   without it the "purge" is incomplete on the surface that actually matters.
4. Re-run the full-history scan against the remote.

---

## 4. Consequences — read before approving

| Consequence | Detail |
|---|---|
| **Every commit SHA changes** | From `e85b57f` (the root commit) onward. Every SHA in every doc, issue, PR, changelog, or bookmark becomes dangling. |
| **All 5 remote branches are rewritten** | `main`, `DAG`, `development`, `Intent-Classification`, `release`. |
| **Existing clones break** | Anyone with a clone gets a non-fast-forward on next pull and must re-clone or hard-reset. |
| **Forks are NOT cleaned** | A fork is an independent repository. The secrets remain in every fork, and GitHub's fork network can still surface the old objects. **This cannot be fixed by pushing.** |
| **Open PRs may be corrupted** | PRs built on rewritten commits typically need reopening. |
| **Old SHAs stay reachable on GitHub** | Until GitHub Support garbage-collects them — see Step 6.3. |
| **Signed commits lose signatures** | Rewriting invalidates any GPG signatures. |
| **CI history / artifacts** | Prior runs point at SHAs that no longer exist on any branch. |

---

## 5. Rollback

```bash
git clone ~/backups/agent-pre-purge.git ~/work/agent-restore
cd ~/work/agent-restore
git push --force --all https://github.com/sumansinghs71/agent.git
git push --force --tags https://github.com/sumansinghs71/agent.git
```

Valid only while the pre-purge mirror and bundle are intact. Do not delete them until the rewrite has
been accepted for at least one release cycle.

---

## 6. Recommendation

Given that the repository is intended as public portfolio material for a security-adjacent role, I
recommend **executing the rewrite** — but only after confirming rotation, and with the honest framing
that the benefit is presentation and hygiene, not risk reduction. The risk was already realised the
moment the values were pushed publicly; rotation is what closed it.

A defensible alternative is to **decline the rewrite** and instead keep the incident visible, with
`SECURITY.md` documenting that the credentials were exposed, rotated, and that scanning now runs in CI.
Some reviewers regard a documented, correctly-handled incident more favourably than a scrubbed history.
Either choice is defensible; a silent scrub while forks still carry the values would not be.

---

## 7. Approval gate

Nothing in §3 Steps 3–5 has been executed. No backup has been taken, no rewrite performed, no push made.

**Approve history rewrite and force-push?**
