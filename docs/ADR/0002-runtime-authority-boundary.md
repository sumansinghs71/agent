# ADR 0002 — The runtime, not the model, authorises tool calls

**Status:** Accepted · **Date:** 2026-08-30 · **Milestone:** M0.9

## Context

`ReasoningAgentService` asked a model to emit JSON containing `action`, `tool_name` and
`parameters`, then passed `tool_name` and `parameters` straight to `ToolExecutionService`. The
runtime enforced depth, call-count and wall-clock limits — all *resource* limits. Nothing enforced
*authority*.

Three inputs reach the planning prompt unchecked: retrieved document chunks, tool descriptions from
the database, and (via a keyword filter only) the user query. Document upload and tool creation were
both unauthenticated. So an attacker could plant text that chose which tool ran and with what
arguments.

Every call site also passed a hardcoded string — `"system"` or `"test"` — as the user id. It looked
like an identity, conveyed no permissions, and was never checked.

## Decision

Introduce `ToolInvocationPolicy`, evaluated before every dispatch, top-level and nested alike.
Denial is the default; any condition that cannot be positively established denies.

Checks: tool exists → enabled → belongs to the tenant → caller authenticated → caller's role covers
the tool's side-effect class → irreversible effects have an approval → arguments match the declared
parameters.

**Authority is an explicit argument, never ambient.** `InvocationPrincipal` is threaded from the
controller through `ChatbotService` and `ReasoningAgentService` into execution, and is held on the
`ExecutionContext` so nested `eztool()` calls are evaluated against the same principal. A
`SecurityContextHolder` thread-local would have been a smaller diff and the wrong design: an
authority check that reads ambient state is one thread hand-off away from checking the wrong
principal, and the failure is invisible. As an argument, a path that forgot to carry it does not
compile.

`InvocationPrincipal.system()` carries no authority and can invoke nothing, replacing the
`"system"` string that implied one.

## Consequences

- Four signatures in `ReasoningAgentService` and two in `ChatbotService` gained a parameter.
- The `executeTool(Long, String, …)` overload is retained but deprecated: a bare user id produces a
  principal with no roles, so every call fails closed rather than failing to compile into an
  unchecked path.
- **Irreversible tools are currently unusable.** `IRREVERSIBLE_WRITE` requires an approval, and no
  approval service exists until M2. Refusing is correct; proceeding unapproved is not.
- Side-effect class is *derived* when not declared. Derivation errs toward danger — an unrecognised
  tool shape is `PRIVILEGED`, never `READ_ONLY`. M3 makes this declarative.
- Argument validation is name/required/undeclared checking, not JSON Schema. Rejecting *undeclared*
  arguments matters as much as requiring declared ones: an extra key is an attempt to reach a code
  path the tool author never described.
