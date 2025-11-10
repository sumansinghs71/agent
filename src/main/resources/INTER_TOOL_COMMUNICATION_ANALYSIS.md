# Inter-Tool Communication System - Deep Analysis

## Executive Summary

This is a **production-grade, enterprise-level inter-tool communication framework** built in Java Spring Boot. The system enables tools (SQL, REST, Python, JavaScript) to call each other recursively with comprehensive safety controls, monitoring, and observability.

**Key Achievement**: Full bidirectional inter-tool communication with context propagation, guardrails, and protocol-based execution.

---

## 1. SYSTEM ARCHITECTURE OVERVIEW

### 1.1 Core Components

```
┌─────────────────────────────────────────────────────────────────┐
│                    ToolExecutionService                          │
│              (Main Orchestration Layer)                          │
└────────────┬────────────────────────────────────────────────────┘
             │
             ├─── ExecutionContextFactory
             │    └─── Creates & manages ExecutionContext per request
             │
             ├─── ToolRegistryService (Caffeine Cache)
             │    └─── O(1) tool lookup with TTL-based cache
             │
             ├─── PythonJavaScriptToolExecutor
             │    ├─── PythonScriptBuilder
             │    ├─── PythonProtocolHandler
             │    ├─── JavaScriptCodeWrapper
             │    └─── EzToolBridge (for JS)
             │
             ├─── CodeValidatorService
             │    └─── Security validation for Python/JS code
             │
             └─── RuntimeGuardrailsService
                  └─── Real-time safety checks during execution
```

### 1.2 Tool Types Supported

| Type       | Execution Method | Inter-Tool Support | Status |
|------------|------------------|-------------------|--------|
| SQL        | JDBC Direct      | ✅ Yes            | Ready  |
| REST       | RestTemplate     | ✅ Yes            | Ready  |
| Python     | Subprocess + Protocol | ✅ Yes       | Ready  |
| JavaScript | GraalVM ScriptEngine | ✅ Yes        | Ready  |

---

## 2. INTER-TOOL COMMUNICATION MECHANISM

### 2.1 The ExecutionContext Pattern

**Critical Innovation**: Every tool execution creates an `ExecutionContext` that:
- Tracks the **call chain** (Tool A → Tool B → Tool C)
- Prevents **circular dependencies** (Tool A calls Tool B calls Tool A)
- Enforces **depth limits** (max 5 levels by default)
- Enforces **total call limits** (max 20 tools per request)
- Manages **aggregate timeouts** (60 seconds total)
- **Auto-cleanup** via try-with-resources

```java
// Example usage
try (ExecutionContext context = contextFactory.create(chatbotId, userId)) {
    ToolExecutionResult result = executeToolInternal(context, request);
    return result;
} // context.close() called automatically - kills processes, cleans resources
```

### 2.2 How Tools Call Each Other

#### For Python Tools:
Python tools can call other tools using the injected `eztool()` function:

```python
def ezMain(data):
    # Call SQL tool to get user data
    user = eztool('getUserById', {'userId': 123})
    
    # Call REST tool to validate
    validation = eztool('validateUser', {'email': user['email']})
    
    # Call JavaScript tool for calculation
    score = eztool('calculateScore', {'data': user})
    
    return {'user': user, 'score': score}
```

**Behind the scenes:**
1. Python prints `###EZTOOL_REQUEST###` + JSON
2. Java reads via stdout, calls `ToolExecutionService.handleEzToolCall()`
3. Java executes the requested tool
4. Java sends result back via `###EZTOOL_RESPONSE###` + JSON
5. Python reads response from stdin and returns it

#### For JavaScript Tools:
JavaScript tools use the injected `eztool` object:

```javascript
function(params) {
    // Call SQL tool
    const users = eztool('getAllUsers', {});
    
    // Call Python tool for analysis
    const analysis = eztool('analyzeData', { data: users });
    
    // Call REST tool
    const external = eztool('fetchExternalData', { id: 123 });
    
    return { users, analysis, external };
}
```

**Behind the scenes:**
- `eztool` is a GraalVM `ProxyExecutable` (Java object)
- Directly calls `ToolExecutionService.handleEzToolCall()`
- No protocol needed - direct method invocation

### 2.3 Protocol Design (Python)

```
PYTHON ─────stdout────→ JAVA
       ←─────stdin─────

Flow:
1. Python emits logs: {"type":"log", "message":"...", ...}
2. Python requests tool: ###EZTOOL_REQUEST### + JSON + ###EZTOOL_REQUEST_END###
3. Java responds: ###EZTOOL_RESPONSE### + JSON + ###EZTOOL_RESPONSE_END###
4. Python returns result: ###RESULT### + JSON
```

**Advantages:**
- Non-blocking
- Streaming logs during execution
- Error handling at protocol level
- Timeout-aware

---

## 3. SAFETY & GUARDRAILS

### 3.1 Code Validation

**Python Dangerous Patterns Blocked:**
- `import os`, `import subprocess`, `import sys`
- `__import__`, `exec()`, `eval()`
- `open()`, `file()`, `input()`
- `import socket`, `import urllib`, `import requests`
- Code with `__` (dunder methods)
- Code > 100KB

**JavaScript Dangerous Patterns Blocked:**
- `eval()`, `setTimeout`, `setInterval`
- `require()`, `import()`, `fetch()`
- `XMLHttpRequest`, `process.exit`
- `child_process`, `__dirname`, `__filename`
- Code > 100KB

### 3.2 Execution Limits

```yaml
max-chain-depth: 5              # Max nested tool calls
max-tool-calls-per-request: 20  # Max total tools per request
aggregate-timeout-ms: 60000     # Total execution time limit
individual-tool-timeout-ms: 30000 # Per-tool timeout
circular-dependency-check: true  # Prevent A→B→A loops
```

### 3.3 Resource Management

- **Process tracking**: All Python subprocesses registered and auto-killed on cleanup
- **Memory limits**: Configurable per-chain memory limits
- **Thread safety**: ReentrantLock for concurrent access
- **Connection pooling**: Dynamic datasource management

### 3.4 Context Propagation

Every nested tool call includes:
```json
{
  "requestId": "abc-123",
  "executionId": "def-456",
  "toolId": "currentToolName",
  "callChain": ["toolA", "toolB", "toolC"]
}
```

This enables:
- Distributed tracing
- Log correlation
- Error tracking
- Performance analysis

---

## 4. LOGGING & OBSERVABILITY

### 4.1 Structured Logging (ezLog)

**Python:**
```python
ezLog("Processing user data", {"userId": 123, "count": 5}, "info")
ezLog("Error occurred", {"error": str(e)}, "error")
```

**JavaScript:**
```javascript
ezLog("Calculating score", {value: 100}, "info");
ezLog("Validation failed", {reason: "invalid"}, "warn");
```

**Output Format:**
```
[PYTHON][2025-11-09T10:30:45Z] [requestId=abc-123] [executionId=def-456] [tool=analyzeData] Processing user data | {"userId": 123, "count": 5}
```

### 4.2 Metrics & Monitoring

- **Execution chain tracking**: Full call graph captured
- **Timing details**: Per-tool and aggregate timing
- **Cache statistics**: Hit/miss rates for tool registry
- **Health checks**: `/actuator/health` with context status
- **Prometheus metrics**: Enabled via Spring Actuator

---

## 5. DATABASE SCHEMA

### 5.1 Tool Definition Table

```sql
CREATE TABLE tool (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  chatbot_id BIGINT NOT NULL,
  func_name_key VARCHAR(255) NOT NULL,
  function_type ENUM('SQL','REST','PYTHON','JAVASCRIPT'),
  
  -- SQL fields
  sql_query TEXT,
  data_source VARCHAR(255),
  
  -- REST fields
  http_method VARCHAR(20),
  http_path VARCHAR(500),
  http_headers JSON,
  http_body TEXT,
  
  -- Python fields
  python_code TEXT,
  
  -- JavaScript fields
  js_code TEXT,
  
  -- Common
  params JSON,
  timeout INT DEFAULT 30000,
  
  UNIQUE KEY (chatbot_id, func_name_key)
);
```

### 5.2 Execution Tracking

```sql
CREATE TABLE tool_execution_log (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  execution_id VARCHAR(255),          -- Unique per execution chain
  parent_tool_id VARCHAR(255),        -- For nested calls
  call_depth INT DEFAULT 0,           -- Depth in chain
  execution_chain JSON,               -- Full call path
  total_tools_called INT DEFAULT 1,   -- Total tools in execution
  status ENUM('SUCCESS','FAILED','TIMEOUT'),
  execution_time_ms INT,
  -- Indexes for performance
  INDEX idx_execution_id (execution_id),
  INDEX idx_parent_tool_id (parent_tool_id)
);
```

---

## 6. CONFIGURATION HIGHLIGHTS

```yaml
tool-execution:
  inter-tool-communication:
    enabled: true
    max-chain-depth: 5
    aggregate-timeout-seconds: 60
    circular-dependency-check: true

  python:
    executor-type: STDIN_STDOUT_PROTOCOL
    interpreter-path: "python3"
    allowed-modules: [json, math, datetime, re, statistics]
    protocol:
      request-marker: "###EZTOOL_REQUEST###"
      response-marker: "###EZTOOL_RESPONSE###"
      result-marker: "###RESULT###"

  javascript:
    executor-type: GRAALVM_BINDING
    
  tool-registry:
    cache-enabled: true
    cache-ttl-seconds: 300
    preload-on-startup: true
    max-cache-size: 1000

  security:
    validate-every-call: true
    max-tool-calls-per-request: 20
    block-dangerous-patterns: true
    enforce-guardrails: true

  monitoring:
    log-execution-chain: true
    enable-metrics: true
    enable-health-checks: true
```

---

## 7. ERROR HANDLING

### 7.1 Exception Hierarchy

```
ToolExecutionException (base)
├── ToolNotFoundException
├── ToolExecutionTimeoutException
├── CircularDependencyException
├── MaxDepthExceededException
├── MaxCallsExceededException
├── CodeValidationException
├── DangerousCodeException
├── ProtocolException
└── ContextClosedException
```

### 7.2 Error Response Format

```json
{
  "success": false,
  "error": "Tool execution failed: Connection timeout",
  "errorCode": "TIMEOUT_ERROR",
  "executionTimeMs": 30500,
  "executionId": "abc-123",
  "callChain": ["toolA", "toolB", "toolC"],
  "metadata": {
    "depth": 3,
    "totalCalls": 5
  }
}
```

---

## 8. PERFORMANCE OPTIMIZATIONS

### 8.1 Tool Registry Caching

- **Caffeine cache** with TTL (5 minutes default)
- **O(1) lookup** for tool definitions
- **Preloading on startup** for hot tools
- **Manual invalidation** for updates
- **Stats tracking** for monitoring

### 8.2 Connection Pooling

- Dynamic datasource management
- Connection reuse for SQL tools
- Configurable pool sizes

### 8.3 Thread Management

- Dedicated executor service for Python/JS
- Configurable thread pool (default: 5 threads)
- Timeout-aware execution

---

## 9. SECURITY FEATURES

### 9.1 Code Sandboxing

**Python:**
- Runs with `--isolated` flag (no site packages)
- Whitelisted modules only
- No file system access
- No network access
- Process-level isolation

**JavaScript:**
- GraalVM polyglot sandbox
- No Node.js modules
- No file system access
- No network access
- Memory limits enforced

### 9.2 Input Validation

- Parameter type checking
- Required field validation
- Default value injection
- SQL injection prevention (parameterized queries)
- XSS prevention in REST calls

### 9.3 Audit Trail

- Every execution logged with:
  - User ID
  - Execution chain
  - Input parameters
  - Output results
  - Timing data
  - Error details

---

## 10. STRENGTHS OF THIS IMPLEMENTATION

### ✅ Enterprise-Grade
- Production-ready error handling
- Comprehensive logging
- Metrics and monitoring
- Health checks
- Configuration management

### ✅ Performance
- Caching layer for tool definitions
- Connection pooling
- Parallel execution support (configurable)
- Optimized protocol for Python

### ✅ Safety
- Multiple layers of validation
- Resource limits at every level
- Auto-cleanup with try-with-resources
- Circular dependency detection
- Timeout enforcement

### ✅ Observability
- Structured logging with context
- Distributed tracing support
- Call chain tracking
- Execution metrics
- Health indicators

### ✅ Flexibility
- Multiple tool types supported
- Extensible architecture
- Configuration-driven
- Hot-reloadable tools (via cache invalidation)


All the hard parts (inter-tool communication, safety, monitoring) are already complete! 🎉
