# Secret Scan Report

**Repository:** `sumansinghs71/agent` · **Branch:** `DAG`
**Date:** 2026-08-30 · **Milestone:** M0.2

> **No secret value appears in this document.** Findings are identified by rule, file, line, and
> commit only. Each secret is referenced by a stable label (`S1`, `S2`).

---

## 1. Scanner

| | |
|---|---|
| Tool | **gitleaks** v8.30.1 (installed via Homebrew) |
| Rule set | built-in default (`--redact` enabled for all runs) |
| Supplementary | targeted `git log -S` / `git grep` for the two secrets identified in the Phase-0 audit |

**Commands run:**

```bash
# 1. Working tree, ignoring git history (catches untracked files too)
gitleaks detect --no-git --source . --report-format json --report-path gl-tree.json --redact

# 2. Full commit history, all reachable commits
gitleaks detect --source . --report-format json --report-path gl-history.json --redact

# 3. Targeted history confirmation (values never echoed)
git log --format='%h %ad %an %s' --date=short -S'<S1>' --all
git log --format='%h %ad %an %s' --date=short -S'<S2>' --all
```

---

## 2. Secrets under management

| Label | Kind | Introduced | Disposition |
|---|---|---|---|
| **S1** | Azure OpenAI API key | `e85b57f` (2025-08-03, "Initial commit") | Removed from tree in M0.1; **rotation owned by repository owner**; still present in history |
| **S2** | MySQL `root` password | `e85b57f` (2025-08-03, "Initial commit") | Removed from tree in M0.1; **rotation owned by repository owner**; still present in history |

---

## 3. Results — working tree

### Before M0.1

| # | Rule | Location | Secret | Disposition |
|---|---|---|---|---|
| 1 | `generic-api-key` | `src/main/resources/application.yml:23` | S1 | **Fixed** — replaced with `${AZURE_OPENAI_API_KEY:}` |
| 2 | *(missed by scanner)* | `src/main/resources/static/datasource.properties:19` | S2 | **Fixed** — replaced with `${BS2_DB_PASSWORD:}` |
| 3 | `generic-api-key` | `CODEBASE_ANALYSIS.md:120` | S1 | **Fixed** — redacted. Untracked, but one `git add -A` would have published it |
| 4 | *(missed by scanner)* | `CODEBASE_ANALYSIS.md:121` | S2 | **Fixed** — redacted |
| 5 | `generic-api-key` | `target/classes/application.yml:23` | S1 | **Fixed** — stale build output; `mvn clean` run, `target/` git-ignored |

Additionally removed in M0.1, though not credentials:

| Item | Location | Disposition |
|---|---|---|
| Personal Azure resource hostnames | `application.yml` (OpenAI + Search endpoints) | Replaced with `${AZURE_OPENAI_ENDPOINT:}` / `${AZURE_SEARCH_ENDPOINT:}` |
| Personal absolute filesystem path | `application.yml` `document.upload-dir` | Replaced with `${DOCUMENT_UPLOAD_DIR:./uploads}` |
| Personal résumé (`.docx`) | `uploads/` | Untracked; `uploads/` and `*.docx` added to `.gitignore` |
| Commented-out example credentials | `datasource.properties` (4 blocks) | File rewritten; blocks deleted |

### After M0.1 — VERIFIED

```
gitleaks detect --no-git --source .
→ INF no leaks found
```

| Metric | Result |
|---|---|
| gitleaks findings in working tree | **0** |
| Literal occurrences of S1 or S2 anywhere in the tree (excl. `.git/`) | **0**, confirmed by direct `grep -rIl` |

---

## 4. Results — git history

**4 findings across 23 commits.** Not remediated; remediation requires history rewrite (see
[GIT_HISTORY_PURGE_PLAN.md](GIT_HISTORY_PURGE_PLAN.md)) which is **pending owner approval**.

| Secret | Commits | File |
|---|---|---|
| S1 | `e85b57f` (×2 lines), `55ee3db`, `63c83b7` | `src/main/resources/application.yml` |
| S2 | `e85b57f`, `b5f2e34`, `55ee3db`, `63c83b7` | `src/main/resources/static/datasource.properties` |

Both secrets are present in the committed tree at `HEAD` (`9d5c4e8`). The M0.1 fixes are working-tree
changes and do not alter history.

**Note on the commit titles.** `55ee3db` and `63c83b7` are both titled *"removed hardcoded credentials"*,
yet both secrets survive at `HEAD`. The earlier cleanup was incomplete. This is precisely the failure
mode that makes scanning-in-CI necessary rather than optional.

---

## 5. Scanner limitation observed (important)

**gitleaks' default rule set detected S1 but did not detect S2.** The Azure key matched
`generic-api-key` on high entropy; the database password
(`BS2.password=…` in a `.properties` file) is short, low-entropy, and human-shaped, so no default rule
fired. S2 was found only by the manual Phase-0 code review.

Consequences, adopted as policy:

1. **A clean gitleaks run is necessary but not sufficient.** It must not be reported as "no secrets exist".
2. A custom rule for `*.properties` credential keys is added below.
3. Code review remains part of the control, not a formality.

`.gitleaks.toml` extends the defaults with a properties-file password rule and allowlists
`.env.example` and this report directory:

```toml
[extend]
useDefault = true

[[rules]]
id = "properties-credential"
description = "Credential assigned in a .properties/.yml file"
# RE2 has no lookahead; require the value's first char to be neither '$' nor '<'
regex = '''(?i)^[\w.]*\.?(password|passwd|pwd|secret|api[-_]?key|token)\s*[:=]\s*[^$<\s]\S{5,}'''
path = '''\.(properties|ya?ml)$'''

[rules.allowlist]
regexTarget = "line"
regexes = ['''(?i)(replace-me|changeme|example|placeholder|redacted)''']

[allowlist]
paths = ['''\.env\.example$''', '''^docs/security/''', '''^target/''']
```

---

## 6. CI integration

Secret scanning runs on every push and pull request via `.github/workflows/security.yml`
(`gitleaks/gitleaks-action@v2`, `fetch-depth: 0` so full history is scanned). **The job fails the
build on any finding.** Until the history purge is approved and executed, the history findings in §4
are expected; the workflow therefore scans with `--no-git` on PRs (tree must always be clean) and runs
the full-history scan as a separate, currently-informational job — see the workflow comments.

---

## 7. Disposition summary

| Question | Answer |
|---|---|
| Secrets in the working tree? | **No** — 0 findings, verified |
| Secrets in git history? | **Yes** — 4 findings, 2 distinct secrets, 4 commits |
| Have the secrets been rotated? | **Owner-managed, outside git.** Repository owner has stated rotation is done/in progress. Rotation is the real control. |
| Is history remediated? | **No** — plan prepared, awaiting explicit approval |
| Is scanning enforced in CI? | **Yes** — added in M0.11 |
