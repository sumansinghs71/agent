# Security Policy

## Status of this project

**This is a research and portfolio project undergoing active security hardening. It is not
production-ready and must not be deployed to handle real traffic, real credentials, or real user
data.**

It executes model-selected tools, including code, so its threat model is inherently sharp. Treat it
as a laboratory, not a product.

## Reporting a vulnerability

Open a GitHub issue for anything already public (such as an item in
[docs/00_CURRENT_STATE_AUDIT.md](docs/00_CURRENT_STATE_AUDIT.md)).

For anything not yet public, please use GitHub's **private vulnerability reporting** on this
repository rather than a public issue.

## Disclosed incident: credentials committed to history

This repository previously contained two credentials in tracked files, present since the initial
commit:

- an Azure OpenAI API key
- a MySQL `root` password

Both were identified in the M0 audit, **have been removed from the working tree**, and have been
rotated by the repository owner. They remain present in git history pending an approved history
rewrite — see [docs/security/GIT_HISTORY_PURGE_PLAN.md](docs/security/GIT_HISTORY_PURGE_PLAN.md).

This is documented rather than quietly scrubbed, for two reasons. Rotation, not history rewriting,
is what actually closed the exposure — the values were public and must be assumed harvested. And
anyone who forked or cloned this repository before the cleanup still has them in their own copy,
which no amount of force-pushing here can change.

Secret scanning now runs in CI on every push and pull request
(`.github/workflows/security.yml`), and the working tree is a hard gate.

## What is and is not defended

### Enforced, and covered by tests

| Control | Evidence |
|---|---|
| All API endpoints require authentication | `ApiSecurityTest` |
| Tool authoring requires `ROLE_ADMIN` | `ApiSecurityTest` |
| Every tool invocation passes an authority gate | `ToolInvocationPolicyTest` |
| Python executes in a network-less, non-root, read-only, resource-capped container | `DockerSandboxAdversarialTest` (real containers) |
| `LOCAL` (no-isolation) execution refuses to start without an explicit opt-in | `SandboxModeStartupTest` |
| SSRF: private, loopback, link-local and cloud-metadata addresses are blocked | `SsrfGuardTest` |
| Request headers are allowlisted; CR/LF injection is rejected | `RestHeaderPolicyTest` |

### Explicitly NOT defended — do not rely on these

| Gap | Note |
|---|---|
| **Container escape** | Docker shares the host kernel. A kernel or runtime vulnerability is out of scope. This is **not** microVM isolation. |
| **DNS rebinding** | The SSRF guard resolves a name and the HTTP client resolves it again; a name that changes between the two defeats the check. |
| **The code denylist** | `lintPythonCodeBestEffort` is defence-in-depth lint, **not** a boundary. 9 of 10 tested bypasses defeat it. Containment is the sandbox's job. |
| **Prompt injection** | Retrieved documents and tool descriptions reach the planner unchecked. The authority gate limits the damage; it does not prevent the injection. |
| **JavaScript resource limits** | GraalJS is contained against host access but has no CPU, memory, or statement limit. A hostile JS tool can consume a thread pool slot indefinitely. |
| **Multi-tenancy** | Tenant isolation is a single check on `chatbotId`. It has not been adversarially tested. |

## Supported versions

Only `main`. This project has no releases and offers no backports.
