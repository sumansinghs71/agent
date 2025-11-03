package com.chatbot.agent.service;

import com.chatbot.agent.model.*;
import com.chatbot.agent.repository.ChatbotRepository;
import com.chatbot.agent.service.citation.CitationService;
import com.chatbot.agent.service.guardrails.*;
import com.chatbot.agent.service.tools.ToolExecutionService;
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
    private final InputGuardrailsService inputGuardrailsService;
    private final OutputGuardrailsService outputGuardrailsService;
    private final GuardrailConfigService guardrailConfigService;
    private final GuardrailLogService guardrailLogService;
    private final CitationService citationService;
    private final OllamaService ollamaService;

    public ReasoningAgentService(ChatbotRepository chatbotRepository,
                                 ToolExecutionService toolExecutionService,
                                 AiRouterService aiRouterService,
                                 VectorStoreService vectorStoreService,
                                 AzureSearchService azureSearchService,
                                 ObjectMapper objectMapper,
                                 InputGuardrailsService inputGuardrailsService,
                                 OutputGuardrailsService outputGuardrailsService,
                                 GuardrailConfigService guardrailConfigService,
                                 GuardrailLogService guardrailLogService,
                                 CitationService citationService,
                                 OllamaService ollamaService) {
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
        this.citationService = citationService;
        this.ollamaService =  ollamaService;
    }


    public String processQuery(Long chatbotId, String userQuery) {
        CitationModel.ResponseWithCitations responseWithCitations = processQueryWithCitations(chatbotId, userQuery);
        return formatResponseWithCitations(responseWithCitations);
    }

    /**
     * Main reasoning entry point with guardrails
     */
    public CitationModel.ResponseWithCitations processQueryWithCitations(Long chatbotId, String userQuery) {
        String requestId = MDC.get("requestId");
        log.info("[requestId={}] ReasoningAgent processing query with citations for chatbot: {}",
                requestId, chatbotId);

        try {
            // STEP 0: Get guardrail configuration
            GuardrailModel.GuardrailConfig config = guardrailConfigService.getConfig(chatbotId);

            // STEP 1: INPUT GUARDRAILS
            log.info("[requestId={}] Running INPUT guardrails validation", requestId);
            GuardrailModel.GuardrailResult inputValidation =
                    inputGuardrailsService.validateInput(userQuery, config);

            if (!inputValidation.isAllowed()) {
                log.warn("[requestId={}] Input BLOCKED by guardrails: type={}, severity={}",
                        requestId,
                        inputValidation.getViolation().getType(),
                        inputValidation.getViolation().getSeverity());

                guardrailLogService.logViolation(
                        chatbotId, null,
                        GuardrailModel.GuardrailType.INPUT_VALIDATION,
                        inputValidation, userQuery
                );

                // Return error response with no citations
                return createErrorResponse(formatGuardrailViolationResponse(inputValidation));
            }

            String sanitizedQuery = inputValidation.getSanitizedInput();
            log.info("[requestId={}] Input guardrails PASSED (risk score: {})",
                    requestId, inputValidation.getRiskScore());

            // STEP 2: Get chatbot and tools
            Model.Chatbot chatbot = chatbotRepository.findById(chatbotId)
                    .orElseThrow(() -> new RuntimeException("Chatbot not found: " + chatbotId));

            List<ToolModel.Tool> availableTools = toolExecutionService.getToolsForChatbot(chatbotId);

            // STEP 3: Analyze query intent
            QueryIntent intent = analyzeQueryIntent(chatbot, sanitizedQuery, availableTools);

            log.info("[requestId={}] Query intent determined: actionType={}, confidence={}",
                    requestId, intent.getActionType(), intent.getConfidence());

            // STEP 4: Execute based on intent WITH CITATIONS
            CitationModel.ResponseWithCitations responseWithCitations =
                    executeBasedOnIntentWithCitations(chatbot, sanitizedQuery, intent, availableTools);

            // STEP 5: OUTPUT GUARDRAILS
            log.info("[requestId={}] Running OUTPUT guardrails validation", requestId);

            List<String> sourceContext = extractSourceContextFromCitations(responseWithCitations);

            GuardrailModel.GuardrailResult outputValidation =
                    outputGuardrailsService.validateOutput(
                            responseWithCitations.getAnswer(),
                            sanitizedQuery,
                            sourceContext,
                            config
                    );

            if (!outputValidation.isAllowed()) {
                log.warn("[requestId={}] Output BLOCKED by guardrails: type={}",
                        requestId, outputValidation.getViolation().getType());

                guardrailLogService.logViolation(
                        chatbotId, null,
                        GuardrailModel.GuardrailType.OUTPUT_VALIDATION,
                        outputValidation, responseWithCitations.getAnswer()
                );

                return createErrorResponse(
                        "I apologize, but I cannot provide a reliable answer to your question at this time. " +
                                "Please try rephrasing or contact support."
                );
            }

            log.info("[requestId={}] Output guardrails PASSED (risk score: {})",
                    requestId, outputValidation.getRiskScore());

            // STEP 6: Sanitize output if needed
            if (!outputValidation.getSanitizedInput().equals(responseWithCitations.getAnswer())) {
                responseWithCitations.setAnswer(outputValidation.getSanitizedInput());
            }

            return responseWithCitations;

        } catch (Exception e) {
            log.error("[requestId={}] Error in reasoning agent", requestId, e);
            return createErrorResponse("I encountered an error processing your request: " + e.getMessage());
        }
    }

    /**
     * Execute based on intent and return ResponseWithCitations
     */
    private CitationModel.ResponseWithCitations executeBasedOnIntentWithCitations(
            Model.Chatbot chatbot,
            String userQuery,
            QueryIntent intent,
            List<ToolModel.Tool> availableTools) {

        switch (intent.getActionType()) {
            case TOOL:
                return executeToolActionWithCitations(chatbot, userQuery, intent);
            case DOCUMENT:
                return executeDocumentActionWithCitations(chatbot, userQuery);
            case HYBRID:
                return executeHybridActionWithCitations(chatbot, userQuery, intent);
            case CONVERSATIONAL:
                return executeConversationalActionWithCitations(chatbot, userQuery);
            default:
                return executeDocumentActionWithCitations(chatbot, userQuery);
        }
    }

    /**
     * TOOL action with citations
     */
    private CitationModel.ResponseWithCitations executeToolActionWithCitations(
            Model.Chatbot chatbot,
            String userQuery,
            QueryIntent intent) {

        String requestId = MDC.get("requestId");
        log.info("[requestId={}] Executing TOOL action: {}", requestId, intent.getToolName());

        try {
            ToolModel.ToolExecutionRequest toolRequest = new ToolModel.ToolExecutionRequest();
            toolRequest.setFuncNameKey(intent.getToolName());
            toolRequest.setParams(intent.getParameters() != null ? intent.getParameters() : new HashMap<>());

            ToolExecutionResult result = toolExecutionService.executeTool(
                    chatbot.getId(),"system", toolRequest);

            if (!result.isSuccess()) {
                return createErrorResponse("I tried to fetch the data but encountered an error: " + result.getError());
            }

            // Format tool result with AI
            String response = formatToolResultWithAI(chatbot, userQuery, result.getData());

            // Tool responses don't have document citations, but we can add metadata
            CitationModel.ResponseWithCitations responseWithCitations = new CitationModel.ResponseWithCitations();
            responseWithCitations.setAnswer(response);
            responseWithCitations.setCitations(new ArrayList<>());

            CitationModel.CitationMetadata metadata = new CitationModel.CitationMetadata();
            metadata.setTotalSources(0);
            metadata.setAvgConfidence(1.0f); // Tool data is 100% from source
            metadata.setDocumentsUsed(Collections.singletonList("Tool: " + intent.getToolName()));
            metadata.setTotalChunksRetrieved(0);
            metadata.setCitationsAdded(0);
            responseWithCitations.setMetadata(metadata);

            return responseWithCitations;

        } catch (Exception e) {
            log.error("[requestId={}] Tool execution failed", requestId, e);
            return createErrorResponse("I encountered an error while fetching the data: " + e.getMessage());
        }
    }

    /**
     * DOCUMENT action with citations
     */
    private CitationModel.ResponseWithCitations executeDocumentActionWithCitations(
            Model.Chatbot chatbot,
            String userQuery) {

        String requestId = MDC.get("requestId");
        log.info("[requestId={}] Executing DOCUMENT action with citations", requestId);

        try {
            List<CitationModel.ChunkWithMetadata> chunks;

            // Get chunks with metadata based on model type
            if (chatbot.getModelType() == Model.ModelType.LLAMA) {
                chunks = vectorStoreService.searchChunksWithMetadata(chatbot.getId(), userQuery, 5);
            } else if (chatbot.getModelType() == Model.ModelType.AZURE_OPENAI) {
                chunks = azureSearchService.searchChunksWithMetadata(chatbot.getId(), userQuery, 5);
            } else {
                return createErrorResponse("Unsupported model type");
            }

            if (chunks.isEmpty()) {
                return createErrorResponse("I couldn't find any relevant information in the documents.");
            }

            log.info("[requestId={}] Retrieved {} chunks with metadata", requestId, chunks.size());

            // Build context with instructions
            StringBuilder context = new StringBuilder();

            if (chatbot.getInstructionEnabled() && chatbot.getSystemInstruction() != null) {
                context.append("=== SYSTEM INSTRUCTIONS ===\n");
                context.append(chatbot.getSystemInstruction()).append("\n\n");
            }

            if (chatbot.getInstructionEnabled() && chatbot.getUserInstruction() != null) {
                context.append("=== USER INSTRUCTIONS ===\n");
                context.append(chatbot.getUserInstruction()).append("\n\n");
            }

            context.append("=== DOCUMENT CONTEXT ===\n");
            for (CitationModel.ChunkWithMetadata chunk : chunks) {
                context.append(chunk.getChunkText()).append("\n\n");
            }

            // Generate response
            String response;
            if (chatbot.getModelType() == Model.ModelType.LLAMA) {
                response = ollamaService.generateResponse(userQuery, context.toString());
            } else {
                response = aiRouterService.callAzureOpenAiWithContext(userQuery, context.toString());
            }

            // Add citations
            CitationModel.ResponseWithCitations responseWithCitations =
                    citationService.addCitations(response, chunks);

            log.info("[requestId={}] Added {} citations to response",
                    requestId, responseWithCitations.getCitations().size());

            return responseWithCitations;

        } catch (Exception e) {
            log.error("[requestId={}] Document search failed", requestId, e);
            return createErrorResponse("I couldn't find relevant information: " + e.getMessage());
        }
    }

    /**
     * HYBRID action with citations
     */
    private CitationModel.ResponseWithCitations executeHybridActionWithCitations(
            Model.Chatbot chatbot,
            String userQuery,
            QueryIntent intent) {

        String requestId = MDC.get("requestId");
        log.info("[requestId={}] Executing HYBRID action with citations", requestId);

        try {
            // Execute tool
            ToolModel.ToolExecutionRequest toolRequest = new ToolModel.ToolExecutionRequest();
            toolRequest.setFuncNameKey(intent.getToolName());
            toolRequest.setParams(intent.getParameters() != null ? intent.getParameters() : new HashMap<>());

            ToolExecutionResult toolResult = toolExecutionService.executeTool(
                    chatbot.getId(), "system",toolRequest);

            // Get document chunks
            List<CitationModel.ChunkWithMetadata> chunks;
            if (chatbot.getModelType() == Model.ModelType.LLAMA) {
                chunks = vectorStoreService.searchChunksWithMetadata(chatbot.getId(), userQuery, 3);
            } else {
                chunks = azureSearchService.searchChunksWithMetadata(chatbot.getId(), userQuery, 3);
            }

            // Build context with tool data and documents
            StringBuilder context = new StringBuilder();

            if (chatbot.getInstructionEnabled() && chatbot.getSystemInstruction() != null) {
                context.append("=== SYSTEM INSTRUCTIONS ===\n");
                context.append(chatbot.getSystemInstruction()).append("\n\n");
            }

            if (chatbot.getInstructionEnabled() && chatbot.getUserInstruction() != null) {
                context.append("=== USER INSTRUCTIONS ===\n");
                context.append(chatbot.getUserInstruction()).append("\n\n");
            }

            // Add tool data
            try {
                String toolDataJson = objectMapper.writeValueAsString(toolResult.getData());
                context.append("=== TOOL DATA ===\n");
                context.append(toolDataJson).append("\n\n");
            } catch (Exception e) {
                log.warn("Failed to serialize tool data", e);
            }

            // Add document context
            context.append("=== DOCUMENT CONTEXT ===\n");
            for (CitationModel.ChunkWithMetadata chunk : chunks) {
                context.append(chunk.getChunkText()).append("\n\n");
            }

            // Generate response
            String prompt = "User asked: \"" + userQuery + "\"\n\n" +
                    "Using the tool data and document context above, provide a comprehensive response.";

            String response = aiRouterService.routeToAi(chatbot.getModelType(), context + "\n" + prompt);

            // Add citations (only from documents, not tool data)
            CitationModel.ResponseWithCitations responseWithCitations =
                    citationService.addCitations(response, chunks);

            // Update metadata to include tool info
            if (responseWithCitations.getMetadata() != null) {
                List<String> docsUsed = new ArrayList<>(responseWithCitations.getMetadata().getDocumentsUsed());
                docsUsed.add("Tool: " + intent.getToolName());
                responseWithCitations.getMetadata().setDocumentsUsed(docsUsed);
            }

            return responseWithCitations;

        } catch (Exception e) {
            log.error("[requestId={}] Hybrid execution failed", requestId, e);
            return createErrorResponse("I encountered an error: " + e.getMessage());
        }
    }

    /**
     * CONVERSATIONAL action with citations (no citations expected)
     */
    private CitationModel.ResponseWithCitations executeConversationalActionWithCitations(
            Model.Chatbot chatbot,
            String userQuery) {

        String requestId = MDC.get("requestId");
        log.info("[requestId={}] Executing CONVERSATIONAL action", requestId);

        StringBuilder prompt = new StringBuilder();

        if (chatbot.getInstructionEnabled() && chatbot.getSystemInstruction() != null) {
            prompt.append("=== SYSTEM INSTRUCTIONS ===\n");
            prompt.append(chatbot.getSystemInstruction()).append("\n\n");
        }

        if (chatbot.getInstructionEnabled() && chatbot.getUserInstruction() != null) {
            prompt.append("=== USER INSTRUCTIONS ===\n");
            prompt.append(chatbot.getUserInstruction()).append("\n\n");
        }

        prompt.append(userQuery);

        String response = aiRouterService.routeToAi(chatbot.getModelType(), prompt.toString());

        // Conversational responses have no citations
        CitationModel.ResponseWithCitations responseWithCitations = new CitationModel.ResponseWithCitations();
        responseWithCitations.setAnswer(response);
        responseWithCitations.setCitations(new ArrayList<>());

        CitationModel.CitationMetadata metadata = new CitationModel.CitationMetadata();
        metadata.setTotalSources(0);
        metadata.setAvgConfidence(0.0f);
        metadata.setDocumentsUsed(Collections.emptyList());
        metadata.setTotalChunksRetrieved(0);
        metadata.setCitationsAdded(0);
        responseWithCitations.setMetadata(metadata);

        return responseWithCitations;
    }

    /**
     * Format ResponseWithCitations as text string (for backward compatibility)
     */
    private String formatResponseWithCitationss(CitationModel.ResponseWithCitations responseWithCitations) {
        if (responseWithCitations.getCitations() == null || responseWithCitations.getCitations().isEmpty()) {
            return responseWithCitations.getAnswer();
        }

        StringBuilder formatted = new StringBuilder();
        formatted.append(responseWithCitations.getAnswer());
        formatted.append(citationService.formatCitationsAsText(responseWithCitations.getCitations()));

        return formatted.toString();
    }

    /**
     * Extract source context from citations for output validation
     */
    private List<String> extractSourceContextFromCitations(CitationModel.ResponseWithCitations response) {
        if (response.getCitations() == null || response.getCitations().isEmpty()) {
            return Collections.emptyList();
        }

        return response.getCitations().stream()
                .map(CitationModel.Citation::getExcerpt)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    /**
     * Create error response
     */
    private CitationModel.ResponseWithCitations createErrorResponse(String errorMessage) {
        CitationModel.ResponseWithCitations response = new CitationModel.ResponseWithCitations();
        response.setAnswer(errorMessage);
        response.setCitations(new ArrayList<>());

        CitationModel.CitationMetadata metadata = new CitationModel.CitationMetadata();
        metadata.setTotalSources(0);
        metadata.setAvgConfidence(0.0f);
        metadata.setDocumentsUsed(Collections.emptyList());
        metadata.setTotalChunksRetrieved(0);
        metadata.setCitationsAdded(0);
        response.setMetadata(metadata);

        return response;
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

            ToolExecutionResult result = toolExecutionService.executeTool(
                    chatbot.getId(), "system",toolRequest);

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
        log.info("[requestId={}] Executing DOCUMENT action with citations", requestId);

        try {
            if (chatbot.getModelType() == Model.ModelType.LLAMA) {
                // Get chunks with metadata
                List<CitationModel.ChunkWithMetadata> chunks =
                        vectorStoreService.searchChunksWithMetadata(chatbot.getId(), userQuery, 5);

                if (chunks.isEmpty()) {
                    return "I couldn't find any relevant information in the documents.";
                }

                // Build context with instructions
                StringBuilder context = new StringBuilder();

                if (chatbot.getInstructionEnabled() && chatbot.getSystemInstruction() != null) {
                    context.append("=== SYSTEM INSTRUCTIONS ===\n");
                    context.append(chatbot.getSystemInstruction()).append("\n\n");
                }

                if (chatbot.getInstructionEnabled() && chatbot.getUserInstruction() != null) {
                    context.append("=== USER INSTRUCTIONS ===\n");
                    context.append(chatbot.getUserInstruction()).append("\n\n");
                }

                context.append("=== DOCUMENT CONTEXT ===\n");
                for (CitationModel.ChunkWithMetadata chunk : chunks) {
                    context.append(chunk.getChunkText()).append("\n\n");
                }

                // Generate response
                String response = ollamaService.generateResponse(userQuery, context.toString());

                // Add citations
                CitationModel.ResponseWithCitations responseWithCitations =
                        citationService.addCitations(response, chunks);

                // Format final response with citations
                return formatResponseWithCitationss(responseWithCitations);

            } else if (chatbot.getModelType() == Model.ModelType.AZURE_OPENAI) {
                // Get chunks with metadata
                List<CitationModel.ChunkWithMetadata> chunks =
                        azureSearchService.searchChunksWithMetadata(chatbot.getId(), userQuery, 5);

                if (chunks.isEmpty()) {
                    return "I couldn't find any relevant information in the documents.";
                }

                log.info("[requestId={}] Retrieved {} chunks with metadata", requestId, chunks.size());

                // Build context
                StringBuilder context = new StringBuilder();

                if (chatbot.getInstructionEnabled() && chatbot.getSystemInstruction() != null) {
                    context.append("=== SYSTEM INSTRUCTIONS ===\n");
                    context.append(chatbot.getSystemInstruction()).append("\n\n");
                }

                if (chatbot.getInstructionEnabled() && chatbot.getUserInstruction() != null) {
                    context.append("=== USER INSTRUCTIONS ===\n");
                    context.append(chatbot.getUserInstruction()).append("\n\n");
                }

                context.append("=== DOCUMENT CONTEXT ===\n");
                for (CitationModel.ChunkWithMetadata chunk : chunks) {
                    context.append(chunk.getChunkText()).append("\n\n");
                }

                // Generate response
                String response = aiRouterService.callAzureOpenAiWithContext(userQuery, context.toString());

                // Add citations
                CitationModel.ResponseWithCitations responseWithCitations =
                        citationService.addCitations(response, chunks);

                // Format final response with citations
                return formatResponseWithCitationss(responseWithCitations);
            }

            return "Unsupported model type";

        } catch (Exception e) {
            log.error("[requestId={}] Document search failed", requestId, e);
            return "I couldn't find relevant information: " + e.getMessage();
        }
    }

    /**
     * Format response with citations
     */
    private String formatResponseWithCitations(CitationModel.ResponseWithCitations responseWithCitations) {
        StringBuilder formatted = new StringBuilder();

        // Add the answer with inline citations
        formatted.append(responseWithCitations.getAnswer());

        // Add citation list
        if (responseWithCitations.getCitations() != null &&
                !responseWithCitations.getCitations().isEmpty()) {

            formatted.append(citationService.formatCitationsAsText(responseWithCitations.getCitations()));
        }

        return formatted.toString();
    }

    private String executeHybridAction(Model.Chatbot chatbot, String userQuery, QueryIntent intent) {
        String requestId = MDC.get("requestId");
        log.info("[requestId={}] Executing HYBRID action", requestId);

        try {
            ToolModel.ToolExecutionRequest toolRequest = new ToolModel.ToolExecutionRequest();
            toolRequest.setFuncNameKey(intent.getToolName());
            toolRequest.setParams(intent.getParameters() != null ? intent.getParameters() : new HashMap<>());

            ToolExecutionResult toolResult = toolExecutionService.executeTool(
                    chatbot.getId(), "system",toolRequest);

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
