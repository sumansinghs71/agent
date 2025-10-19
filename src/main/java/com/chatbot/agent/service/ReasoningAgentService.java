package com.chatbot.agent.service;

import com.chatbot.agent.model.Model;
import com.chatbot.agent.model.ToolModel;
import com.chatbot.agent.model.GuardrailModel;
import com.chatbot.agent.repository.ChatbotRepository;
import com.chatbot.agent.service.guardrails.*;
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

    // Guardrail services
    private final InputGuardrailsService inputGuardrailsService;
    private final OutputGuardrailsService outputGuardrailsService;
    private final GuardrailConfigService guardrailConfigService;
    private final GuardrailLogService guardrailLogService;

    public ReasoningAgentService(ChatbotRepository chatbotRepository,
                                 ToolExecutionService toolExecutionService,
                                 AiRouterService aiRouterService,
                                 VectorStoreService vectorStoreService,
                                 AzureSearchService azureSearchService,
                                 ObjectMapper objectMapper,
                                 InputGuardrailsService inputGuardrailsService,
                                 OutputGuardrailsService outputGuardrailsService,
                                 GuardrailConfigService guardrailConfigService,
                                 GuardrailLogService guardrailLogService) {
        this.chatbotRepository = chatbotRepository;
        this.toolExecutionService = toolExecutionService;
        this.aiRouterService = aiRouterService;
        this.vectorStoreService = vectorStoreService;
        this.azureSearchService = azureSearchService;
        this.objectMapper = objectMapper;
        this.inputGuardrailsService = inputGuardrailsService;
        this.outputGuardrailsService = outputGuardrailsService;
        this.guardrailConfigService = guardrailConfigService;
        this.guardrailLogService = guardrailLogService;
    }

    /**
     * Main reasoning entry point with guardrails
     */
    public String processQuery(Long chatbotId, String userQuery) {
        String requestId = MDC.get("requestId");
        log.info("[requestId={}] ReasoningAgent processing query for chatbot: {}", requestId, chatbotId);

        try {
            // STEP 0: Get guardrail configuration
            GuardrailModel.GuardrailConfig config = guardrailConfigService.getConfig(chatbotId);

            // STEP 1: INPUT GUARDRAILS - Validate user input FIRST
            log.info("[requestId={}] Running INPUT guardrails validation", requestId);
            GuardrailModel.GuardrailResult inputValidation =
                    inputGuardrailsService.validateInput(userQuery, config);

            if (!inputValidation.isAllowed()) {
                log.warn("[requestId={}] Input BLOCKED by guardrails: type={}, severity={}",
                        requestId,
                        inputValidation.getViolation().getType(),
                        inputValidation.getViolation().getSeverity());

                // Log the violation
                guardrailLogService.logViolation(
                        chatbotId,
                        null, // sessionId - add if you have session tracking
                        GuardrailModel.GuardrailType.INPUT_VALIDATION,
                        inputValidation,
                        userQuery
                );

                // Return safe response to user
                return formatGuardrailViolationResponse(inputValidation);
            }

            // Use sanitized input (PII may have been redacted)
            String sanitizedQuery = inputValidation.getSanitizedInput();
            log.info("[requestId={}] Input guardrails PASSED (risk score: {})",
                    requestId, inputValidation.getRiskScore());

            // STEP 2: Get chatbot and tools
            Model.Chatbot chatbot = chatbotRepository.findById(chatbotId)
                    .orElseThrow(() -> new RuntimeException("Chatbot not found: " + chatbotId));

            List<ToolModel.Tool> availableTools = toolExecutionService.getToolsForChatbot(chatbotId);

            // STEP 3: Analyze query intent and decide action (using sanitized query)
            QueryIntent intent = analyzeQueryIntent(chatbot, sanitizedQuery, availableTools);

            log.info("[requestId={}] Query intent determined: actionType={}, confidence={}",
                    requestId, intent.getActionType(), intent.getConfidence());

            // STEP 4: Execute based on intent
            String response = executeBasedOnIntent(chatbot, sanitizedQuery, intent, availableTools);

            // STEP 5: OUTPUT GUARDRAILS - Validate AI response
            log.info("[requestId={}] Running OUTPUT guardrails validation", requestId);

            List<String> sourceContext = extractSourceContext(intent, chatbotId, sanitizedQuery);

            GuardrailModel.GuardrailResult outputValidation =
                    outputGuardrailsService.validateOutput(
                            response,
                            sanitizedQuery,
                            sourceContext,
                            config
                    );

            if (!outputValidation.isAllowed()) {
                log.warn("[requestId={}] Output BLOCKED by guardrails: type={}",
                        requestId, outputValidation.getViolation().getType());

                // Log the violation
                guardrailLogService.logViolation(
                        chatbotId,
                        null,
                        GuardrailModel.GuardrailType.OUTPUT_VALIDATION,
                        outputValidation,
                        response
                );

                // Return fallback response
                return "I apologize, but I cannot provide a reliable answer to your question at this time. Please try rephrasing or contact support.";
            }

            log.info("[requestId={}] Output guardrails PASSED (risk score: {})",
                    requestId, outputValidation.getRiskScore());

            // Return sanitized output (may have PII redacted)
            return outputValidation.getSanitizedInput();

        } catch (Exception e) {
            log.error("[requestId={}] Error in reasoning agent", requestId, e);
            return "I encountered an error processing your request: " + e.getMessage();
        }
    }

    /**
     * Format user-friendly response for guardrail violations
     */
    private String formatGuardrailViolationResponse(GuardrailModel.GuardrailResult result) {
        GuardrailModel.GuardrailViolation violation = result.getViolation();

        switch (violation.getType()) {
            case JAILBREAK_ATTEMPT:
            case PROMPT_INJECTION:
                return "I cannot process this request. Please rephrase your query in a straightforward manner.";

            case TOXIC_CONTENT:
                return "Please keep your messages respectful and appropriate.";

            case EXCESSIVE_LENGTH:
                return "Your message is too long. Please shorten it and try again.";

            default:
                return "I cannot process this request. " + violation.getRecommendation();
        }
    }

    /**
     * Extract source context for output validation
     */
    private List<String> extractSourceContext(QueryIntent intent, Long chatbotId, String query) {
        List<String> context = new ArrayList<>();

        try {
            if (intent.getActionType() == ActionType.DOCUMENT ||
                    intent.getActionType() == ActionType.HYBRID) {

                Model.Chatbot chatbot = chatbotRepository.findById(chatbotId).orElse(null);
                if (chatbot != null) {
                    if (chatbot.getModelType() == Model.ModelType.AZURE_OPENAI) {
                        context = azureSearchService.searchRelevantChunks(chatbotId, query, 5);
                    }
                }
            }
        } catch (Exception e) {
            log.error("Error extracting source context", e);
        }

        return context;
    }

    private QueryIntent analyzeQueryIntent(Model.Chatbot chatbot, String userQuery,
                                           List<ToolModel.Tool> availableTools) {
        String requestId = MDC.get("requestId");
        String intentPrompt = buildIntentClassificationPrompt(userQuery, availableTools, chatbot);

        log.info("[requestId={}] Sending intent classification to AI", requestId);
        String aiResponse = aiRouterService.routeToAi(chatbot.getModelType(), intentPrompt);

        return parseIntentResponse(aiResponse, availableTools);
    }

    private String buildIntentClassificationPrompt(String userQuery, List<ToolModel.Tool> availableTools, Model.Chatbot chatbot){
        StringBuilder prompt = new StringBuilder();

        // ADD CUSTOM SYSTEM INSTRUCTION FIRST
        if (chatbot.getInstructionEnabled() != null && chatbot.getInstructionEnabled() &&
                chatbot.getSystemInstruction() != null && !chatbot.getSystemInstruction().isEmpty()) {
            prompt.append("=== SYSTEM INSTRUCTIONS ===\n");
            prompt.append(chatbot.getSystemInstruction()).append("\n\n");
            prompt.append("=== END SYSTEM INSTRUCTIONS ===\n\n");
        }

        prompt.append("You are an intelligent agent that decides how to answer user queries.\n\n");

        // ADD CUSTOM USER INSTRUCTION
        if (chatbot.getInstructionEnabled() != null && chatbot.getInstructionEnabled() &&
                chatbot.getUserInstruction() != null && !chatbot.getUserInstruction().isEmpty()) {
            prompt.append("=== USER INSTRUCTIONS ===\n");
            prompt.append(chatbot.getUserInstruction()).append("\n\n");
            prompt.append("=== END USER INSTRUCTIONS ===\n\n");
        }
        prompt.append("You have access to:\n");
        prompt.append("1. DOCUMENTS - Knowledge base containing:\n");
        prompt.append("   - User resumes and profiles\n");
        prompt.append("   - Personal information (skills, experience, education)\n");
        prompt.append("   - Company policies and procedures\n");
        prompt.append("   - General knowledge and FAQs\n");
        prompt.append("2. TOOLS - External APIs and databases for:\n");
        prompt.append("   - Fetching real-time data\n");
        prompt.append("   - Querying external systems\n");
        prompt.append("   - Executing operations\n\n");

        if (!availableTools.isEmpty()) {
            prompt.append("Available tools:\n");
            for (ToolModel.Tool tool : availableTools) {
                prompt.append("- Tool: ").append(tool.getFuncNameKey()).append("\n");
                prompt.append("  Description: ").append(tool.getPrompt()).append("\n");
                prompt.append("  Type: ").append(tool.getFunctionType()).append("\n"); // Add type
                if (tool.getParams() != null) {
                    String params = tool.getParams().stream()
                            .map(p -> p.getParamNameKey() + "(" + p.getParamType() + ")")
                            .collect(Collectors.joining(", "));
                    prompt.append("  Parameters: ").append(params);
                }
                prompt.append("\n\n");
            }
        }

        prompt.append("User Query: \"").append(userQuery).append("\"\n\n");

        prompt.append("DECISION RULES:\n");
        prompt.append("- Use DOCUMENT for: resume questions, profiles, 'who is X', 'tell me about X', personal info, skills, experience\n");
        prompt.append("- Use TOOL for: external data lookups, API calls, database queries with specific IDs\n");
        prompt.append("- Use HYBRID for: combining personal profile with external data\n");
        prompt.append("- Use CONVERSATIONAL for: greetings, thanks, general chat\n\n");

        prompt.append("IMPORTANT: Names like 'Suman Singh' likely refer to people in uploaded documents/resumes, NOT external API users!\n\n");

        prompt.append("Respond with JSON:\n");
        prompt.append("{\n");
        prompt.append("  \"action\": \"TOOL\" | \"DOCUMENT\" | \"HYBRID\" | \"CONVERSATIONAL\",\n");
        prompt.append("  \"reasoning\": \"detailed explanation\",\n");
        prompt.append("  \"confidence\": 0.0 to 1.0,\n");
        prompt.append("  \"tool_name\": \"tool name if TOOL or HYBRID, null otherwise\",\n");
        prompt.append("  \"parameters\": {\"param1\": \"value1\"}\n");
        prompt.append("}\n");

        return prompt.toString();
    }

    private QueryIntent parseIntentResponse(String aiResponse, List<ToolModel.Tool> availableTools) {
        QueryIntent intent = new QueryIntent();

        try {
            String cleaned = aiResponse.trim()
                    .replaceFirst("^```json\\s*", "")
                    .replaceFirst("^```\\s*", "")
                    .replaceFirst("```\\s*$", "")
                    .trim();

            Map<String, Object> response = objectMapper.readValue(cleaned, Map.class);

            String action = (String) response.get("action");
            intent.setActionType(ActionType.valueOf(action));
            intent.setReasoning((String) response.get("reasoning"));
            intent.setConfidence(((Number) response.getOrDefault("confidence", 0.8)).doubleValue());
            intent.setToolName((String) response.get("tool_name"));
            intent.setParameters((Map<String, Object>) response.get("parameters"));

        } catch (Exception e) {
            log.error("Failed to parse intent, defaulting to DOCUMENT", e);
            intent.setActionType(ActionType.DOCUMENT);
            intent.setReasoning("Failed to parse intent");
            intent.setConfidence(0.5);
        }

        return intent;
    }

    private String executeBasedOnIntent(Model.Chatbot chatbot, String userQuery,
                                        QueryIntent intent, List<ToolModel.Tool> availableTools) {
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
                return executeDocumentAction(chatbot, userQuery);
        }
    }

    private String executeToolAction(Model.Chatbot chatbot, String userQuery, QueryIntent intent) {
        String requestId = MDC.get("requestId");
        log.info("[requestId={}] Executing TOOL action: {}", requestId, intent.getToolName());

        try {
            ToolModel.ToolExecutionRequest toolRequest = new ToolModel.ToolExecutionRequest();
            toolRequest.setFuncNameKey(intent.getToolName());
            toolRequest.setParams(intent.getParameters() != null ? intent.getParameters() : new HashMap<>());

            ToolModel.ToolExecutionResult result = toolExecutionService.executeTool(
                    chatbot.getId(), toolRequest);

            if (!result.isSuccess()) {
                return "I tried to fetch the data but encountered an error: " + result.getError();
            }

            return formatToolResultWithAI(chatbot, userQuery, result.getData());

        } catch (Exception e) {
            log.error("[requestId={}] Tool execution failed", requestId, e);
            return "I encountered an error while fetching the data: " + e.getMessage();
        }
    }

    private String executeDocumentAction(Model.Chatbot chatbot, String userQuery) {
        String requestId = MDC.get("requestId");
        log.info("[requestId={}] Executing DOCUMENT action", requestId);

        try {
            if (chatbot.getModelType() == Model.ModelType.LLAMA) {
                return vectorStoreService.searchAndGenerateResponse(chatbot.getId(), userQuery, chatbot.getSystemInstruction(), chatbot.getUserInstruction());
            } else if (chatbot.getModelType() == Model.ModelType.AZURE_OPENAI) {
                var contextChunks = azureSearchService.searchRelevantChunks(chatbot.getId(), userQuery, 5);
                StringBuilder context = new StringBuilder();

                // ADD INSTRUCTIONS TO CONTEXT
                if (chatbot.getInstructionEnabled() && chatbot.getSystemInstruction() != null) {
                    context.append("=== SYSTEM INSTRUCTIONS ===\n");
                    context.append(chatbot.getSystemInstruction()).append("\n\n");
                }

                if (chatbot.getInstructionEnabled() && chatbot.getUserInstruction() != null) {
                    context.append("=== USER INSTRUCTIONS ===\n");
                    context.append(chatbot.getUserInstruction()).append("\n\n");
                }

                context.append("=== DOCUMENT CONTEXT ===\n");
                for (String chunk : contextChunks) {
                    context.append(chunk).append("\n\n");
                }

                return aiRouterService.callAzureOpenAiWithContext(userQuery, context.toString());
            }
            return "Unsupported model type";
        } catch (Exception e) {
            log.error("[requestId={}] Document search failed", requestId, e);
            return "I couldn't find relevant information: " + e.getMessage();
        }
    }

    private String executeHybridAction(Model.Chatbot chatbot, String userQuery, QueryIntent intent) {
        String requestId = MDC.get("requestId");
        log.info("[requestId={}] Executing HYBRID action", requestId);

        try {
            ToolModel.ToolExecutionRequest toolRequest = new ToolModel.ToolExecutionRequest();
            toolRequest.setFuncNameKey(intent.getToolName());
            toolRequest.setParams(intent.getParameters() != null ? intent.getParameters() : new HashMap<>());

            ToolModel.ToolExecutionResult toolResult = toolExecutionService.executeTool(
                    chatbot.getId(), toolRequest);

            String documentContext = "";
            if (chatbot.getModelType() == Model.ModelType.LLAMA) {
                documentContext = vectorStoreService.searchAndGenerateResponse(chatbot.getId(), userQuery, chatbot.getSystemInstruction(), chatbot.getUserInstruction());
            } else if (chatbot.getModelType() == Model.ModelType.AZURE_OPENAI) {
                var contextChunks = azureSearchService.searchRelevantChunks(chatbot.getId(), userQuery, 3);
                documentContext = String.join("\n\n", contextChunks);
            }

            return formatHybridResultWithAI(chatbot, userQuery, toolResult.getData(), documentContext);

        } catch (Exception e) {
            log.error("[requestId={}] Hybrid execution failed", requestId, e);
            return "I encountered an error: " + e.getMessage();
        }
    }

    private String executeConversationalAction(Model.Chatbot chatbot, String userQuery) {
        String requestId = MDC.get("requestId");
        log.info("[requestId={}] Executing CONVERSATIONAL action", requestId);

        StringBuilder prompt = new StringBuilder();

        // ADD INSTRUCTIONS
        if (chatbot.getInstructionEnabled() && chatbot.getSystemInstruction() != null) {
            prompt.append("=== SYSTEM INSTRUCTIONS ===\n");
            prompt.append(chatbot.getSystemInstruction()).append("\n\n");
        }

        if (chatbot.getInstructionEnabled() && chatbot.getUserInstruction() != null) {
            prompt.append("=== USER INSTRUCTIONS ===\n");
            prompt.append(chatbot.getUserInstruction()).append("\n\n");
        }

        prompt.append(userQuery);

        return aiRouterService.routeToAi(chatbot.getModelType(), prompt.toString());
    }

    private String formatToolResultWithAI(Model.Chatbot chatbot, String userQuery, Object toolData) {
        try {
            String dataJson = objectMapper.writeValueAsString(toolData);

            StringBuilder prompt = new StringBuilder();

            // ADD INSTRUCTIONS
            if (chatbot.getInstructionEnabled() && chatbot.getSystemInstruction() != null) {
                prompt.append("=== SYSTEM INSTRUCTIONS ===\n");
                prompt.append(chatbot.getSystemInstruction()).append("\n\n");
            }

            if (chatbot.getInstructionEnabled() && chatbot.getUserInstruction() != null) {
                prompt.append("=== USER INSTRUCTIONS ===\n");
                prompt.append(chatbot.getUserInstruction()).append("\n\n");
            }

            prompt.append("User asked: \"").append(userQuery).append("\"\n\n");
            prompt.append("Tool data:\n").append(dataJson).append("\n\n");
            prompt.append("Provide a natural, conversational response following the instructions above.");

            return aiRouterService.routeToAi(chatbot.getModelType(), prompt.toString());
        } catch (Exception e) {
            return "Here's what I found: " + toolData.toString();
        }
    }

    private String formatHybridResultWithAI(Model.Chatbot chatbot, String userQuery,
                                            Object toolData, String documentContext) {
        try {
            String dataJson = objectMapper.writeValueAsString(toolData);

            StringBuilder prompt = new StringBuilder();

            if (chatbot.getInstructionEnabled() && chatbot.getSystemInstruction() != null) {
                prompt.append("=== SYSTEM INSTRUCTIONS ===\n");
                prompt.append(chatbot.getSystemInstruction()).append("\n\n");
            }

            if (chatbot.getInstructionEnabled() && chatbot.getUserInstruction() != null) {
                prompt.append("=== USER INSTRUCTIONS ===\n");
                prompt.append(chatbot.getUserInstruction()).append("\n\n");
            }

            prompt.append("User asked: \"").append(userQuery).append("\"\n\n");
            prompt.append("Tool data:\n").append(dataJson).append("\n\n");
            prompt.append("Document context:\n").append(documentContext).append("\n\n");
            prompt.append("Provide a comprehensive response following the instructions above.");

            return aiRouterService.routeToAi(chatbot.getModelType(), prompt.toString());
        } catch (Exception e) {
            return "Here's what I found: " + toolData.toString();
        }
    }

    // Inner classes
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
        TOOL, DOCUMENT, HYBRID, CONVERSATIONAL
    }
}