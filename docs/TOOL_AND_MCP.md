# Tools and MCP

Every tool the runtime will consider — SQL, REST, sandboxed code, or MCP — is described by one
`ToolDefinition`. The authority gate, the planner, the scheduler and the audit trail all reason about
tools through that single type, so adding a protocol does not mean teaching every policy about it.

## The contract

| Field | Purpose |
|---|---|
| `toolId`, `name`, `version`, `description` | identity |
| `inputSchema`, `outputSchema` | JSON Schema, validated before dispatch and after invocation |
| `sideEffectClass` | the axis authorisation is decided on |
| `permissionScopes` | finer constraint on top of roles |
| `tenantId` | ownership; lookup is tenant-scoped |
| `timeout`, `retryPolicy` | execution bounds |
| `idempotencyMode` | how a key is obtained |
| `approvalPolicy`, `requiredApproverRole` | human authorisation |
| `protocol`, `endpoint` | how the tool is reached |
| `enabled` | withdrawal without deletion |

Protocol and side effect are deliberately orthogonal: **how** a tool is invoked says nothing about
**how much damage** it can do. A SQL tool running a `SELECT` and one running a stored procedure share
a protocol and share nothing else that matters.

### Rejected at construction

Two combinations are refused rather than accepted quietly, because each is nearly always a mistake
in the definition:

- `IRREVERSIBLE_WRITE` with `ApprovalPolicy.NONE` — an irreversible effect must require approval.
- An effect-causing tool with no idempotency mode that still permits multiple attempts — it cannot
  be retried safely, so either it gets a mode or it gets one attempt.

## Schema validation

Arguments are validated against the tool's declared JSON Schema before a node is created. This
replaces name-and-required checking, which could tell whether a key was present but not whether its
value made sense: a planner emitting `{"limit": "all rows"}` for an integer parameter passed the
older check and failed inside the tool, where the error is far less legible and — for a
side-effecting tool — potentially after something had already happened.

Results are validated against the output schema. An unchecked malformed result propagates into the
next node's arguments, where the eventual failure points at the wrong tool.

A malformed schema fails closed. Treating "the contract is unreadable" as "the contract is
satisfied" would invert the purpose of having one.

## Legacy tools

Database-backed tools predate the contract and declare no schema, so one is synthesised from their
parameter list with `additionalProperties: false`. The synthesised schema is genuinely weaker than a
hand-written one — names, types and required-ness, but not ranges, enums or cross-field constraints
— and that gap belongs to the tool author. It still rejects undeclared arguments and wrong primitive
types, which is the class of planner error that reaches a tool most often.

Migration cannot silently introduce a repeat-on-retry hazard: an effect-causing legacy tool without
an idempotency mode is given a single attempt.

## MCP

The client implements Model Context Protocol over JSON-RPC 2.0: the `initialize` handshake, the
`notifications/initialized` notification, `tools/list` for discovery, and `tools/call` for
invocation. This is the actual protocol, not a custom JSON API given the name — the value of MCP is
interoperability with servers this project did not write, and a look-alike works only against itself.

| Behaviour | Handling |
|---|---|
| Protocol version mismatch | logged, not rejected — MCP versions are dated revisions and a mismatch is usually still workable |
| Tool failure | MCP reports it in-band via `isError` on a successful response; raised rather than returned as data |
| Unknown tool | JSON-RPC error `-32602`, distinct from a tool that ran and failed |
| Timeout | client-side deadline, then a `notifications/cancelled` to the server |
| Disconnect | reported; the server's tools can be withdrawn from the catalog |

### Side effects are not discoverable over MCP

A server advertises a name, a description and an input schema. **Nothing states whether calling the
tool changes anything.** Since authorisation is decided on the side-effect class, the missing value
is supplied conservatively: a discovered tool is `PRIVILEGED` until an operator classifies it.

Inferring "read-only" from a name like `get_*` would be a naming convention masquerading as a
security control, and it would let an unknown server's tool be invoked by any authenticated user.

Tool ids are namespaced by server (`mcp:<server>:<tool>`) so two servers offering `search` do not
collide.

## The path from proposal to execution

```
planner proposal
  → AgentPlanner        validate every step, or reject the whole plan
    → ExecutionGraph    acyclic by construction
      → AgentRunService persist before executing
        → RunScheduler  claim, execute, checkpoint, recover
```

**One rejected step rejects the whole plan.** Executing the acceptable prefix of a plan whose later
steps were refused produces a partial effect nobody asked for, and leaves the caller unable to tell
what happened from the error.

Direct tool invocation still exists for administrative and debugging use, behind an authenticated
endpoint and its own authority check. What no longer exists is a path where *agent-controlled*
execution avoids the runtime.

## Approval

Requests are durable rows, so a run parked awaiting authorisation survives restart — an approval
workflow that evaporates on deploy is worse than none, because operators learn to expect it and stop
trusting the gate.

| Rule | Behaviour |
|---|---|
| Gate position | before the effect, never after |
| Wrong role | refused |
| `FOUR_EYE` | the requester may not approve their own request |
| Second decision | refused; the first stands |
| Expiry | a lapsed request cannot be granted; approving into an elapsed window would make expiry advisory |
| Rejection | node terminal, dependents skipped |

`EXPIRED` is distinct from `REJECTED`: nobody decided against the action, nobody decided at all.

Evidence: [results/M3_TOOLS_MCP_APPROVAL_RESULTS.md](results/M3_TOOLS_MCP_APPROVAL_RESULTS.md).
