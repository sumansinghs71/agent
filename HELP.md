# Reasoning Agent - How It Works

## Overview
The reasoning agent analyzes each user query and decides the best approach:
- **TOOL**: Execute a database query or API call
- **DOCUMENT**: Search uploaded documents (RAG)
- **HYBRID**: Use both tool data and document context
- **CONVERSATIONAL**: Simple conversation, no data needed

## Flow Diagram
```
User Query → Reasoning Agent → Intent Classification → Action Execution → Response
                                      ↓
                        ┌─────────────┴─────────────┐
                        │                           │
                    [Analyze]                  [Match Tools]
                        │                           │
                   Extract Intent              Find Relevant Tools
                        │                           │
                        └─────────────┬─────────────┘
                                      ↓
                              Choose Action Type
                                      ↓
                    ┌─────────────────┼───────────────────┐
                    ↓                 ↓                   ↓
                 [TOOL]            [DOCUMENT]           [HYBRID]
                    │                 │                   │
            Execute SQL/API      Search Vector DB    Execute Both
                    │                 │                   │
                    └─────────────────┴───────────────────┘
                                      ↓
                                Format with AI
                                      ↓
                                Return to User
```

## Example Scenarios

### 1. TOOL Action
**User Query**: "How many projects does John Doe have?"

**AI Analysis**:
```json
{
  "action": "TOOL",
  "reasoning": "Query asks for specific count data from database",
  "confidence": 0.95,
  "tool_name": "getCountOfProjects",
  "parameters": {
    "owner": "John Doe"
  }
}
```

**Execution**:
1. Execute SQL: `SELECT count(*) FROM projects WHERE owner = 'John Doe'`
2. Get result: `[{"cnt": 42}]`
3. Format with AI: "John Doe has 42 projects."

### 2. DOCUMENT Action
**User Query**: "What is the project onboarding process?"

**AI Analysis**:
```json
{
  "action": "DOCUMENT",
  "reasoning": "Query asks for procedural information likely in documents",
  "confidence": 0.90,
  "tool_name": null,
  "parameters": null
}
```

**Execution**:
1. Generate embedding for query
2. Search vector database for top 5 relevant chunks
3. Pass chunks to AI with user query
4. AI generates answer based on document context

### 3. HYBRID Action
**User Query**: "Show me John's projects and explain the approval workflow"

**AI Analysis**:
```json
{
  "action": "HYBRID",
  "reasoning": "Query needs both specific data (projects) and process info (workflow)",
  "confidence": 0.85,
  "tool_name": "getCountOfProjects",
  "parameters": {
    "owner": "John"
  }
}
```

**Execution**:
1. Execute tool to get John's projects
2. Search documents for "approval workflow"
3. Combine both results
4. AI generates comprehensive response

## API Usage Examples

### 1. Create a SQL Tool
```json
{
  "funcNameKey": "getCountOfProjects",
  "label": "Number of Projects",
  "prompt": "Get the number of projects created by a specific owner",
  "params": [
    {
      "paramNameKey": "owner",
      "paramType": "string",
      "paramDescription": "User name",
      "required": true,
      "defaultValue": null
    }
  ],
  "functionType": "SQL",
  "dataSource": "BS1",
  "sqlQuery": "SELECT count(*) as cnt FROM projects WHERE LOWER(owner_id) = LOWER(:owner)",
  "timeout": 30000,
  "columns": [
    {
      "columnId": "cnt",
      "label": "Count",
      "type": "number"
    }
  ]
}
```

### 2. Create a REST API Tool
```json
{
  "funcNameKey": "getClientAUC",
  "label": "Get Client AUC",
  "prompt": "Retrieves Assets under Custody value for a client",
  "params": [
    {
      "paramNameKey": "ucmClientMnemonic",
      "paramType": "string",
      "paramDescription": "Client mnemonic code",
      "required": true
    }
  ],
  "functionType": "REST",
  "dataSource": null,
  "httpMethod": "POST",
  "httpPath": "https://api.example.com/client/{{$ucmClientMnemonic}}/auc",
  "httpHeaders": {
    "accept": "application/json",
    "Content-Type": "application/json",
    "Authorization": "Bearer {{$token}}"
  },
  "httpBody": "{\"type\":\"EOD\"}",
  "timeout": 30000
}
```

## Real-World Integration Examples

### Example 1: E-commerce Order System
**Scenario**: Customer asks about their order status

**Tools Setup**:
```json
{
  "funcNameKey": "getOrderStatus",
  "label": "Get Order Status",
  "prompt": "Retrieves the current status of a customer order",
  "params": [
    {
      "paramNameKey": "orderId",
      "paramType": "string",
      "paramDescription": "Order ID",
      "required": true
    }
  ],
  "functionType": "SQL",
  "dataSource": "BS1",
  "sqlQuery": "SELECT order_id, status, total_amount, created_at FROM orders WHERE order_id = :orderId",
  "timeout": 30000
}
```

**Example Flow**:
1. User Query: "What's the status of my order #12345?"
2. AI analyzes query → Identifies need for getOrderStatus tool
3. Extracts parameter: orderId = "12345"
4. Executes SQL query
5. Returns: "Your order #12345 is currently 'In Transit' and was placed on Dec 1st with a total of $149.99"

### Example 2: Financial Dashboard
**Scenario**: User wants account balance and investment performance

**Tools Setup**:
```json
[
  {
    "funcNameKey": "getAccountBalance",
    "prompt": "Get current account balance for a customer",
    "params": [{"paramNameKey": "accountId", "paramType": "string", "required": true}],
    "functionType": "REST",
    "httpMethod": "GET",
    "httpPath": "https://api.bank.com/accounts/{{$accountId}}/balance"
  },
  {
    "funcNameKey": "getInvestmentPerformance",
    "prompt": "Get investment portfolio performance",
    "params": [{"paramNameKey": "portfolioId", "paramType": "string", "required": true}],
    "functionType": "SQL",
    "dataSource": "BS2",
    "sqlQuery": "SELECT symbol, shares, current_value, gain_loss FROM portfolio WHERE portfolio_id = :portfolioId"
  }
]
```

## Advanced Patterns & Optimization

### Pattern 1: Multi-Step Tool Execution
**Query**: "Show me high-value orders from last month that haven't shipped yet"

**Reasoning**:
1. First tool call: `getOrdersByDateRange(startDate, endDate)`
2. Filter in memory: orders > $500
3. Second tool call: `getShippingStatus(orderId)` for each
4. Aggregate results
5. Format response

### Pattern 2: Conditional Tool Selection
**Query**: "Get me the latest sales report"

**AI Decision Tree**:
- Is "latest" = today? → Use API (real-time data)
- Is "latest" = last month? → Use SQL (historical data)
- Is format needed? → Chain with document generation tool

### Performance Optimization Tips

#### 1. Tool Caching
```java
@Cacheable(value = "tools", key = "#chatbotId")
public List<ToolModel.Tool> getToolsForChatbot(Long chatbotId) {
    return toolRepository.findByChatbotId(chatbotId);
}
```

#### 2. Parallel Execution for HYBRID
```java
CompletableFuture<Object> toolFuture = CompletableFuture.supplyAsync(
    () -> toolExecutionService.executeTool(chatbotId, request)
);

CompletableFuture<String> docFuture = CompletableFuture.supplyAsync(
    () -> vectorStoreService.searchAndGenerateResponse(chatbotId, query)
);

// Wait for both
CompletableFuture.allOf(toolFuture, docFuture).join();
```

#### 3. Streaming Responses
```java
@GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public Flux<String> streamChat(@RequestParam Long chatbotId, 
                              @RequestParam String message) {
    return Flux.create(sink -> {
        sink.next("Analyzing query...");
        sink.next("Executing tool...");
        sink.next("Formatting response...");
        sink.next(finalResponse);
        sink.complete();
    });
}
```
