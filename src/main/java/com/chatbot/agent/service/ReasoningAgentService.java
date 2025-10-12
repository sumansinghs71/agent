package com.chatbot.agent.service;

import com.chatbot.agent.model.Model;
import com.chatbot.agent.model.ToolModel;
import com.chatbot.agent.repository.ChatbotRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class ReasoningAgentService {

    private static final Logger log = LoggerFactory.getLogger(ReasoningAgentService.class);

    private final ChatbotRepository chatbotRepository;
    private final ToolExecutionService toolExecutionService;
    private final AiRouterService aiRouterService;
    private final VectorStoreService vectorStoreService;
    private final AzureSearchService azureSearchService;
    private final ObjectMapper objectMapper;

    public ReasoningAgentService(ChatbotRepository chatbotRepository,
                                 ToolExecutionService toolExecutionService,
                                 AiRouterService aiRouterService,
                                 VectorStoreService vectorStoreService,
                                 AzureSearchService azureSearchService,
                                 ObjectMapper objectMapper) {
        this.chatbotRepository = chatbotRepository;
        this.toolExecutionService = toolExecutionService;
        this.aiRouterService = aiRouterService;
        this.vectorStoreService = vectorStoreService;
        this.azureSearchService = azureSearchService;
        this.objectMapper = objectMapper;
    }

    /**
     * Main reasoning entry point - decides whether to use tools, documents, or both
     */
    public String processQuery(Long chatbotId, String userQuery) {
        String requestId = MDC.get("requestId");
        log.info("[requestId={}] ReasoningAgent processing query for chatbot: {}", requestId, chatbotId);

        try {
            Model.Chatbot chatbot = chatbotRepository.findById(chatbotId)
                    .orElseThrow(() -> new RuntimeException("Chatbot not found: " + chatbotId));

            // Step 1: Get available tools
            List<ToolModel.Tool> availableTools = toolExecutionService.getToolsForChatbot(chatbotId);

            // Step 2: Analyze query intent and decide action
            QueryIntent intent = analyzeQueryIntent(chatbot, userQuery, availableTools);

            log.info("[requestId={}] Query intent determined: actionType={}, confidence={}",
                    requestId, intent.getActionType(), intent.getConfidence());

            // Step 3: Execute based on intent
            return executeBasedOnIntent(chatbot, userQuery, intent, availableTools);

        } catch (Exception e) {
            log.error("[requestId={}] Error in reasoning agent", requestId, e);
            return "I encountered an error processing your request: " + e.getMessage();
        }
    }

    /**
     * Analyzes the user query to determine intent
     */
    private QueryIntent analyzeQueryIntent(Model.Chatbot chatbot, String userQuery,
                                           List<ToolModel.Tool> availableTools) {
        String requestId = MDC.get("requestId");

        // Build prompt for intent classification
        String intentPrompt = buildIntentClassificationPrompt(userQuery, availableTools);

        log.info("[requestId={}] Sending intent classification prompt to AI", requestId);

        // Call AI to classify intent
        String aiResponse = aiRouterService.routeToAi(chatbot.getModelType(), intentPrompt);

        log.info("[requestId={}] AI intent classification response: {}", requestId, aiResponse);

        // Parse AI response
        return parseIntentResponse(aiResponse, availableTools);
    }

    /**
     * Builds the prompt for intent classification
     */
    private String buildIntentClassificationPrompt(String userQuery, List<ToolModel.Tool> availableTools) {
        StringBuilder prompt = new StringBuilder();

        prompt.append("You are an intelligent agent that decides how to answer user queries.\n\n");
        prompt.append("You have access to:\n");
        prompt.append("1. DOCUMENTS - Knowledge base with general information\n");
        prompt.append("2. TOOLS - Specific functions to query databases or APIs\n\n");

        if (!availableTools.isEmpty()) {
            prompt.append("Available tools:\n");
            for (ToolModel.Tool tool : availableTools) {
                prompt.append("- Tool: ").append(tool.getFuncNameKey()).append("\n");
                prompt.append("  Description: ").append(tool.getPrompt()).append("\n");
                prompt.append("  Parameters: ");
                if (tool.getParams() != null) {
                    String params = tool.getParams().stream()
                            .map(p -> p.getParamNameKey() + "(" + p.getParamType() + ")")
                            .collect(Collectors.joining(", "));
                    prompt.append(params);
                }
                prompt.append("\n\n");
            }
        }

        prompt.append("User Query: \"").append(userQuery).append("\"\n\n");

        prompt.append("Analyze the query and respond with JSON in this exact format:\n");
        prompt.append("{\n");
        prompt.append("  \"action\": \"TOOL\" | \"DOCUMENT\" | \"HYBRID\" | \"CONVERSATIONAL\",\n");
        prompt.append("  \"reasoning\": \"explanation of why you chose this action\",\n");
        prompt.append("  \"confidence\": 0.0 to 1.0,\n");
        prompt.append("  \"tool_name\": \"name of tool if action is TOOL or HYBRID\",\n");
        prompt.append("  \"parameters\": {\"param1\": \"extracted_value\", ...}\n");
        prompt.append("}\n\n");

        prompt.append("Guidelines:\n");
        prompt.append("- Use TOOL if query asks for specific data that a tool can provide\n");
        prompt.append("- Use DOCUMENT if query asks for general knowledge or explanations\n");
        prompt.append("- Use HYBRID if query needs both tool data and document context\n");
        prompt.append("- Use CONVERSATIONAL if query is a greeting or general conversation\n");
        prompt.append("- Extract parameter values from the user query\n");
        prompt.append("- Respond ONLY with valid JSON, no additional text\n");

        return prompt.toString();
    }

    /**
     * Parses the AI's intent classification response
     */
    private QueryIntent parseIntentResponse(String aiResponse, List<ToolModel.Tool> availableTools) {
        QueryIntent intent = new QueryIntent();

        try {
            // Clean response - remove markdown code blocks if present
            String cleaned = aiResponse.trim();
            if (cleaned.startsWith("```json")) {
                cleaned = cleaned.substring(7);
            }
            if (cleaned.startsWith("```")) {
                cleaned = cleaned.substring(3);
            }
            if (cleaned.endsWith("```")) {
                cleaned = cleaned.substring(0, cleaned.length() - 3);
            }
            cleaned = cleaned.trim();

            // Parse JSON
            Map<String, Object> response = objectMapper.readValue(cleaned, Map.class);

            String action = (String) response.get("action");
            intent.setActionType(ActionType.valueOf(action));
            intent.setReasoning((String) response.get("reasoning"));
            intent.setConfidence(((Number) response.getOrDefault("confidence", 0.8)).doubleValue());
            intent.setToolName((String) response.get("tool_name"));
            intent.setParameters((Map<String, Object>) response.get("parameters"));

        } catch (Exception e) {
            log.error("Failed to parse intent response, defaulting to DOCUMENT", e);
            // Default to document search if parsing fails
            intent.setActionType(ActionType.DOCUMENT);
            intent.setReasoning("Failed to parse intent, using default");
            intent.setConfidence(0.5);
        }

        return intent;
    }

    /**
     * Executes the appropriate action based on intent
     */
    private String executeBasedOnIntent(Model.Chatbot chatbot, String userQuery,
                                        QueryIntent intent, List<ToolModel.Tool> availableTools) {
        String requestId = MDC.get("requestId");

        switch (intent.getActionType()) {
            case TOOL:
                return executeToolAction(chatbot, userQuery, intent);

            case DOCUMENT:
                return executeDocumentAction(chatbot, userQuery);

            case HYBRID:
                return executeHybridAction(chatbot, userQuery, intent);

            case CONVERSATIONAL:
                return executeConversationalAction(chatbot, userQuery);

            default:
                log.warn("[requestId={}] Unknown action type: {}", requestId, intent.getActionType());
                return executeDocumentAction(chatbot, userQuery);
        }
    }

    /**
     * Executes tool-based action
     */
    private String executeToolAction(Model.Chatbot chatbot, String userQuery, QueryIntent intent) {
        String requestId = MDC.get("requestId");
        log.info("[requestId={}] Executing TOOL action: {}", requestId, intent.getToolName());

        try {
            // Execute the tool
            ToolModel.ToolExecutionRequest toolRequest = new ToolModel.ToolExecutionRequest();
            toolRequest.setFuncNameKey(intent.getToolName());
            toolRequest.setParams(intent.getParameters() != null ? intent.getParameters() : new HashMap<>());

            ToolModel.ToolExecutionResult result = toolExecutionService.executeTool(
                    chatbot.getId(), toolRequest
            );

            if (!result.isSuccess()) {
                return "I tried to fetch the data but encountered an error: " + result.getError();
            }

            // Format the tool result with AI
            return formatToolResultWithAI(chatbot, userQuery, result.getData());

        } catch (Exception e) {
            log.error("[requestId={}] Tool execution failed", requestId, e);
            return "I encountered an error while fetching the data: " + e.getMessage();
        }
    }

    /**
     * Executes document-based action (RAG)
     */
    private String executeDocumentAction(Model.Chatbot chatbot, String userQuery) {
        String requestId = MDC.get("requestId");
        log.info("[requestId={}] Executing DOCUMENT action", requestId);

        try {
            if (chatbot.getModelType() == Model.ModelType.LLAMA) {
                return vectorStoreService.searchAndGenerateResponse(chatbot.getId(), userQuery);
            } else if (chatbot.getModelType() == Model.ModelType.AZURE_OPENAI) {
                var contextChunks = azureSearchService.searchRelevantChunks(chatbot.getId(), userQuery, 5);
                StringBuilder context = new StringBuilder();
                for (String chunk : contextChunks) {
                    context.append(chunk).append("\n\n");
                }
                return aiRouterService.callAzureOpenAiWithContext(userQuery, context.toString());
            }
            return "Unsupported model type";
        } catch (Exception e) {
            log.error("[requestId={}] Document search failed", requestId, e);
            return "I couldn't find relevant information in the documents: " + e.getMessage();
        }
    }

    /**
     * Executes hybrid action (both tool and documents)
     */
    private String executeHybridAction(Model.Chatbot chatbot, String userQuery, QueryIntent intent) {
        String requestId = MDC.get("requestId");
        log.info("[requestId={}] Executing HYBRID action", requestId);

        try {
            // Step 1: Execute tool to get data
            ToolModel.ToolExecutionRequest toolRequest = new ToolModel.ToolExecutionRequest();
            toolRequest.setFuncNameKey(intent.getToolName());
            toolRequest.setParams(intent.getParameters() != null ? intent.getParameters() : new HashMap<>());

            ToolModel.ToolExecutionResult toolResult = toolExecutionService.executeTool(
                    chatbot.getId(), toolRequest
            );

            // Step 2: Get relevant document context
            String documentContext = "";
            if (chatbot.getModelType() == Model.ModelType.LLAMA) {
                var contextChunks = vectorStoreService.searchAndGenerateResponse(chatbot.getId(), userQuery);
                documentContext = contextChunks;
            } else if (chatbot.getModelType() == Model.ModelType.AZURE_OPENAI) {
                var contextChunks = azureSearchService.searchRelevantChunks(chatbot.getId(), userQuery, 3);
                documentContext = String.join("\n\n", contextChunks);
            }

            // Step 3: Combine both and generate final response
            return formatHybridResultWithAI(chatbot, userQuery, toolResult.getData(), documentContext);

        } catch (Exception e) {
            log.error("[requestId={}] Hybrid execution failed", requestId, e);
            return "I encountered an error while processing your request: " + e.getMessage();
        }
    }

    /**
     * Executes conversational action (no tool or document needed)
     */
    private String executeConversationalAction(Model.Chatbot chatbot, String userQuery) {
        String requestId = MDC.get("requestId");
        log.info("[requestId={}] Executing CONVERSATIONAL action", requestId);

        return aiRouterService.routeToAi(chatbot.getModelType(), userQuery);
    }

    /**
     * Formats tool result using AI for natural language response
     */
    private String formatToolResultWithAI(Model.Chatbot chatbot, String userQuery, Object toolData) {
        try {
            String dataJson = objectMapper.writeValueAsString(toolData);

            String prompt = "User asked: \"" + userQuery + "\"\n\n" +
                    "The tool returned this data:\n" +
                    dataJson + "\n\n" +
                    "Please provide a natural, conversational response to the user's question " +
                    "using this data. Be concise and clear.";

            return aiRouterService.routeToAi(chatbot.getModelType(), prompt);
        } catch (Exception e) {
            log.error("Failed to format tool result", e);
            return "Here's what I found: " + toolData.toString();
        }
    }

    /**
     * Formats hybrid result (tool + documents) using AI
     */
    private String formatHybridResultWithAI(Model.Chatbot chatbot, String userQuery,
                                            Object toolData, String documentContext) {
        try {
            String dataJson = objectMapper.writeValueAsString(toolData);

            String prompt = "User asked: \"" + userQuery + "\"\n\n" +
                    "Tool data:\n" + dataJson + "\n\n" +
                    "Additional context from documents:\n" + documentContext + "\n\n" +
                    "Please provide a comprehensive response combining both the tool data " +
                    "and document context to fully answer the user's question.";

            return aiRouterService.routeToAi(chatbot.getModelType(), prompt);
        } catch (Exception e) {
            log.error("Failed to format hybrid result", e);
            return "Here's what I found: " + toolData.toString();
        }
    }

    /**
     * Query Intent representation
     */
    public static class QueryIntent {
        private ActionType actionType;
        private String reasoning;
        private double confidence;
        private String toolName;
        private Map<String, Object> parameters;

        public ActionType getActionType() { return actionType; }
        public void setActionType(ActionType actionType) { this.actionType = actionType; }

        public String getReasoning() { return reasoning; }
        public void setReasoning(String reasoning) { this.reasoning = reasoning; }

        public double getConfidence() { return confidence; }
        public void setConfidence(double confidence) { this.confidence = confidence; }

        public String getToolName() { return toolName; }
        public void setToolName(String toolName) { this.toolName = toolName; }

        public Map<String, Object> getParameters() { return parameters; }
        public void setParameters(Map<String, Object> parameters) { this.parameters = parameters; }
    }

    public enum ActionType {
        TOOL,           // Use tool only
        DOCUMENT,       // Search documents only
        HYBRID,         // Use both tool and documents
        CONVERSATIONAL  // No tool/document needed, just conversation
    }
}