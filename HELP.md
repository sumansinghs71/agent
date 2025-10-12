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
                    ┌───────────────────┼───────────────────┐
                    ↓                   ↓                   ↓
                [TOOL]            [DOCUMENT]           [HYBRID]
                    │                   │                   │
            Execute SQL/API      Search Vector DB    Execute Both
                    │                   │                   │
                    └───────────────────┴───────────────────┘
                                        ↓
                              Format with AI
                                        ↓
                            Return to User
```

## Example Scenarios

### Scenario 1: TOOL Action
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

---

### Scenario 2: DOCUMENT Action
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

---

### Scenario 3: HYBRID Action
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

---

### Scenario 4: CONVERSATIONAL Action
**User Query**: "Hello, how are you?"

**AI Analysis**:
```json
{
  "action": "CONVERSATIONAL",
  "reasoning": "Simple greeting, no data retrieval needed",
  "confidence": 0.99,
  "tool_name": null,
  "parameters": null
}
```

**Execution**:
1. Pass directly to AI model
2. Return conversational response

---

## API Usage Examples

### 1. Create a SQL Tool
```bash
POST /api/tools/1
Content-Type: application/json

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
```bash
POST /api/tools/1
Content-Type: application/json

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

### 3. Chat with Reasoning
```bash
POST /api/chatbots/1/chat
Content-Type: application/json

"How many projects does Alice have?"
```

**Response**:
```
Alice has 15 projects currently in the system.
```

### 4. Test Tool Directly
```bash
POST /api/tools/1/execute
Content-Type: application/json

{