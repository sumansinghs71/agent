# Typed Tools, MCP and Approval: Results

**Verified:** 2026-08-31 · **Components:** tool contract, planner boundary, MCP client, approval workflow

## Problem

Three defects, all structural rather than incidental:

1. **Argument checking could not express a contract.** Validation confirmed that declared parameters
   were present and undeclared ones absent. It could not check that a value had the right type, so a
   planner emitting prose for an integer parameter passed and failed inside the tool.
2. **Agent-controlled execution bypassed the runtime.** The reasoning service called the tool
   executor directly: no durable record, no recovery, no approval gate, and an authority check that
   ran per-call rather than over the plan as a whole.
3. **`WAITING_APPROVAL` was a state with nothing behind it.** The enum value and its table existed;
   nothing requested or granted an approval.

## Method

- One canonical `ToolDefinition` for every protocol, with JSON Schema on both input and output.
- A planner boundary that compiles proposals into validated graphs or rejects them whole.
- An MCP client speaking JSON-RPC 2.0, tested against a demo server that implements the protocol
  surface rather than mirroring the client's expectations.
- A durable approval store with role checks, separation of duty, expiry, and once-only decisions.

## Observed results

**246 tests, 0 failures, 0 errors.** 50 new. Overall coverage 34.5% → **40.4%**.

| Package | Line coverage |
|---|---|
| `runtime.approval` | 93.3% |
| `runtime.plan` | 92.6% |
| `tools.mcp` | 91.3% |
| `tools.registry` | 82.3% |
| `tools.contract` | 79.0% |

### Planner boundary (15 tests, real PostgreSQL)

| Property | Observed |
|---|---|
| An accepted plan becomes durable rows and events | `RUN_CREATED`, `NODE_CLAIMED`, `NODE_SUCCEEDED` present |
| Unknown tool | plan rejected, **0 invocations** |
| Cross-tenant tool | reported `UNKNOWN_TOOL`, indistinguishable from nonexistent |
| Disabled tool | plan rejected |
| Wrong argument type | plan rejected before any node exists |
| Undeclared argument | plan rejected |
| Missing required argument | plan rejected |
| Insufficient authority | plan rejected |
| **One bad step among good ones** | **whole plan rejected, 0 invocations** |
| Cyclic proposal | refused at graph construction |
| Anonymous principal | rejected |
| Idempotency key | 64-hex SHA-256, stable across argument ordering |

### MCP (13 tests)

| Property | Observed |
|---|---|
| `initialize` handshake | server identity returned; operations before it refused |
| `tools/list` | 3 tools with input schemas intact |
| `tools/call` | correct results returned |
| Tool failure (`isError`) | raised, not returned as data |
| Unknown tool | JSON-RPC `-32602` surfaced |
| Unavailable server | failed in under 5s rather than hanging |
| Mid-session disconnect | reported |
| Mapping | MCP tools registered alongside SQL and REST in one catalog |
| Unclassified tool | **defaults to `PRIVILEGED`**, never read-only |
| Tool ids | namespaced by server; two servers do not collide |

### Approval (10 tests, real PostgreSQL)

| Property | Observed |
|---|---|
| Irreversible node parks before its effect | `WAITING_APPROVAL`, **0 effects** |
| Approval resumes the same run | `SUCCEEDED`, effect happens **exactly once** |
| Rejection | node terminal, dependents `SKIPPED`, 0 effects |
| Wrong role | refused, request stays `PENDING` |
| Four-eye self-approval | refused; a different qualified approver succeeds |
| Second decision | refused; the first stands |
| Expired request | cannot be granted; node fails; 0 effects |
| **Restart while waiting** | request readable by a fresh process; run completed by a different scheduler |
| Read-only node | no approval requested |

## Limitations

- **`ReasoningAgentService` is not yet rebuilt on this path.** `RuntimeBackedAgentService` is the
  supported route and is fully tested, but the existing reasoning service still calls the tool
  executor directly. The boundary exists and is enforced for anything using it; the legacy service
  has not been migrated onto it.
- **MCP transport is in-process.** The protocol layer is real and tested; a stdio transport for
  out-of-process servers is not implemented.
- **`ApprovalPolicy.CUSTOM` is declared but not implemented.**
- **Output schemas are unused in practice.** Validation is implemented and tested; no current tool
  declares one.
- **Side-effect classification for MCP tools is manual.** The protocol carries no such information,
  so an operator must classify each discovered tool or accept `PRIVILEGED`.

## Reproduction

```bash
docker pull postgres:16-alpine
./mvnw test -Dtest='PlannerToRuntimeTest,McpIntegrationTest,ApprovalWorkflowTest,LegacyToolAdapterTest,ToolCatalogTest'
```

The PostgreSQL-backed suites are `@EnabledIf(dockerAvailable)` and skip without a daemon; CI asserts
they ran.
