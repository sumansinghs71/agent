# ADR 0001 — Container isolation is the security boundary; the denylist is not

**Status:** Accepted · **Date:** 2026-08-30 · **Milestone:** M0.5 / M0.6

## Context

Python tool execution was guarded by a regex denylist of ~16 literal strings (`import os`,
`exec(`, …) plus a rule rejecting any code containing `__`. The generated script was then run by
`ProcessBuilder` against the host interpreter. The shipped default was `sandbox: LOCAL`.

Replaying the exact production pattern list against ten candidate payloads, **nine passed**:
`importlib.import_module('os')`, `import  os` with two spaces, a tab instead of a space,
`shutil.os`, `platform.os`, `pathlib` writes, an infinite loop, and a fork bomb. The denylist blocks
one spelling of one import.

Meanwhile `tool-execution.python.allowed-modules` reads like an import allowlist but is only used to
emit `import` lines into the generated preamble. It restricts nothing.

## Decision

1. **The container is the boundary.** `DOCKER` becomes the shipped default.
2. **`LOCAL` cannot start silently.** It requires `AGENT_ALLOW_UNSAFE_LOCAL_EXECUTION=true`;
   otherwise startup fails. A refused boot beats a running process with no isolation.
3. **The denylist survives only as lint**, renamed `lintPythonCodeBestEffort`, with a comment saying
   it is not a boundary. The dead `CodeValidatorService` — which looked like the security control but
   had zero references — is deleted.
4. **Containment is proven by attack, not by inspection.** `DockerSandboxCommandTest` asserts the
   flags appear in the argv; `DockerSandboxAdversarialTest` launches real containers and attacks
   them. A flag can be present and ineffective.

## Consequences

- Python tools now need a reachable Docker daemon. This is a real operational cost, accepted
  deliberately: the alternative is no isolation.
- Container startup is ~100–300ms per execution. Pooling is deferred to M6, where it can be measured.
- Writing the adversarial suite immediately found a bug in the hardening itself: explicit `--tmpfs`
  options *replace* Docker's defaults, so the workspace mounted root-owned `0755` and the non-root
  sandbox user could not write to it — and the same change had silently removed `/tmp`'s usual
  `1777`. Fixed with `uid=`/`gid=`, which is tighter than `mode=1777` because only the sandbox user
  can write. The flag-level test would never have caught this.

## What is explicitly not claimed

Docker shares the host kernel. This is **not** microVM isolation, and a container escape via a kernel
vulnerability is out of scope. A gVisor or Firecracker adapter behind the existing `PythonSandbox`
SPI is the path if that threat model is ever required.
