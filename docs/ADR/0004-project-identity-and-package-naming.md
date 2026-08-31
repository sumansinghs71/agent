# ADR 0004 — Rename Maven coordinates now; defer the Java package rename

**Status:** Accepted · **Date:** 2026-08-30 · **Milestone:** M1

## Context

The project shipped as `com.chatbot:hybrid-chatbot:1.0.0`, named *"Hybrid AI Chatbot Platform"*.
That describes what it was before the runtime work, not what it is. The public identity is
**Agent Runtime Lab**.

Version `1.0.0` also overstated maturity: there has never been a release, and `SECURITY.md` states
plainly that the project is not production-ready.

## Decision

Maven coordinates become `io.github.sumansinghs71:agent-runtime-lab:0.1.0-SNAPSHOT`, with the real
name, description, URL and licence declared in the POM.

`0.1.0-SNAPSHOT` rather than `1.0.0`: a version number is a claim about stability, and `1.0.0` on a
project whose own security policy says "do not deploy this" is a claim that contradicts the
documentation next to it.

**The Java package `com.chatbot.agent` is deliberately left alone for now.**

## Why the package rename is deferred

Renaming the package touches every one of ~90 source files. Three reasons not to do it yet:

1. **It would bury the security history.** The M0 commit is the most substantive thing in this
   repository. A mechanical 90-file rename landing next to it makes `git log --follow` and
   `git blame` markedly worse for exactly the code a reviewer would want to trace.
2. **It is cosmetic.** No behaviour, no security property, and no build coordinate depends on it.
   The artifact a consumer sees is already correct.
3. **The right moment is M2.** The durable-runtime work introduces a genuinely new package tree
   (`runtime`, `graph`, `state`). Renaming then folds the churn into a change that is already
   restructuring the code, instead of spending a large diff on nothing.

The mismatch is recorded here rather than left as a silent inconsistency someone has to rediscover.

## Consequences

- The published artifact name matches the project identity; the internal package does not, until M2.
- The GitHub repository is still named `agent`. Renaming it is deferred pending owner confirmation,
  since it changes clone URLs and any external link. GitHub does redirect, but redirects are not a
  substitute for knowing what breaks.
