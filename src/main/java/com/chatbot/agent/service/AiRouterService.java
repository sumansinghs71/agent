package com.chatbot.agent.service;


import com.chatbot.agent.model.Model;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

@Service
public class AiRouterService {

    private static final Logger log = LoggerFactory.getLogger(AiRouterService.class);

    @Value("${azure.openai.base-url}")
    private String azureOpenAiUrl;

    @Value("${azure.openai.api-key}")
    private String azureApiKey;

    @Value("${azure.openai.deployment-name}")
    private String deploymentName;

    @Value("${llama.base-url}")
    private String llamaUrl;

    private final RestTemplate restTemplate;
    private final com.chatbot.agent.metrics.AgentMetrics metrics;

    public AiRouterService(@Qualifier("restTemplate") RestTemplate restTemplate,
                           com.chatbot.agent.metrics.AgentMetrics metrics) {
        this.restTemplate = restTemplate;
        this.metrics = metrics;
    }

    public String routeToAi(Model.ModelType modelType, String prompt) {
        if (modelType == Model.ModelType.AZURE_OPENAI) {
            return callAzureOpenAiWithRetry(prompt, 2);
        } else {
            return callLlama(prompt);
        }
    }

    /** Total time this method may spend sleeping between attempts. */
    private static final long MAX_RETRY_BUDGET_MS = 8_000;
    private static final long BASE_BACKOFF_MS = 500;

    /**
     * Retry on 429 with bounded, jittered exponential backoff.
     *
     * <p>The previous implementation slept a flat 60 seconds per attempt, twice, so a rate-limited
     * provider blocked the calling request thread for two minutes with no upper bound on how many
     * threads could be parked that way. Backoff is now capped by {@link #MAX_RETRY_BUDGET_MS} in
     * total, and jittered so that a fleet of retrying callers does not resynchronise into a
     * thundering herd against a provider that is already overloaded.
     */
    private String callAzureOpenAiWithRetry(String prompt, int maxRetries) {
        long sleptMs = 0;

        for (int attempt = 0; attempt < maxRetries; attempt++) {
            try {
                return callAzureOpenAi(prompt);
            } catch (HttpClientErrorException.TooManyRequests e) {
                if (attempt == maxRetries - 1) {
                    break;
                }

                long backoff = Math.min(BASE_BACKOFF_MS << attempt, MAX_RETRY_BUDGET_MS - sleptMs);
                if (backoff <= 0) {
                    break;
                }
                // Full jitter: sleep somewhere in [0, backoff).
                long jittered = ThreadLocalRandom.current().nextLong(backoff + 1);

                log.warn("Azure OpenAI returned 429; retrying attempt {} of {} after {}ms",
                        attempt + 2, maxRetries, jittered);
                try {
                    Thread.sleep(jittered);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted while backing off from Azure OpenAI", ie);
                }
                sleptMs += jittered;
            }
        }

        throw new RuntimeException("Exceeded max retries for Azure OpenAI due to rate limiting.");
    }

    private String callAzureOpenAi(String prompt) {
        String requestId = MDC.get("requestId");
        HttpHeaders headers = new HttpHeaders();
        headers.set("api-key", azureApiKey);
        headers.setContentType(MediaType.APPLICATION_JSON);

        // Build messages array for chat API
        var messages = java.util.List.of(
            java.util.Map.of("role", "user", "content", prompt)
        );

        Map<String, Object> request = Map.of(
                "messages", messages,
                "max_tokens", 1600,
                "temperature", 0.7,
                "top_p", 0.95,
                "frequency_penalty", 0,
                "presence_penalty", 0
        );

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(request, headers);

        String url = String.format("%s/openai/deployments/%s/chat/completions?api-version=2025-01-01-preview",
                azureOpenAiUrl, deploymentName);

        log.info("[requestId={}] AiRouterService.callAzureOpenAi request: url={}", requestId, url);
        ResponseEntity<Map> response = metrics.timeLlm("azure_openai", deploymentName, "chat_completion",
                () -> restTemplate.exchange(url, HttpMethod.POST, entity, Map.class));
        log.debug("[requestId={}] AiRouterService.callAzureOpenAi response received", requestId);
        return extractChatCompletionText(response.getBody());
    }

    private String callLlama(String prompt) {
        String requestId = MDC.get("requestId");
        String url = llamaUrl + "/api/generate";
        String sanitizedPrompt = prompt
                .replace("\\", "\\\\")  // escape backslashes
                .replace("\"", "\\\"")  // escape quotes
                .replace("\n", "\\n");  // escape newlines

        String requestBody = String.format(
                "{\"model\": \"llama2\", \"prompt\": \"%s\", \"stream\": false}", sanitizedPrompt
        );
//        String requestBody = String.format("{\"text\": \"%s\"}", prompt);
        log.info("[requestId={}] AiRouterService.callLlama request: url={}", requestId, url);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<String> entity = new HttpEntity<>(requestBody, headers);
        String response = metrics.timeLlm("ollama", "llama2", "generate",
                () -> restTemplate.postForObject(url, entity, String.class));

        log.debug("[requestId={}] AiRouterService.callLlama response received", requestId);
        return extractInnerJson(response);
    }

    public String extractInnerJson(String fullResponse) {
        int startIndex = fullResponse.indexOf("\"response\":\"") + "\"response\":\"".length();
        int endIndex = fullResponse.indexOf("\",\"done\":");

        if (startIndex >= 0 && endIndex > startIndex) {
            String rawJson = fullResponse.substring(startIndex, endIndex);
            // Unescape JSON string
            String cleanedJson = rawJson.replace("\\n", "\n").replace("\\\"", "\"");
            return cleanedJson;
        }
        return null;
    }

    public String callAzureOpenAiWithContext(String question, String context) {
        String requestId = MDC.get("requestId");
        HttpHeaders headers = new HttpHeaders();
        headers.set("api-key", azureApiKey);
        headers.setContentType(MediaType.APPLICATION_JSON);

        // Use SYSTEM_PROMPT as the system message, and append context
        String systemPrompt = "You are an intelligent bot for resume handling. Answer user questions based on the provided context. If the answer is not in the context, say you don't know. Context: " + context;
        var messages = java.util.List.of(
            java.util.Map.of("role", "system", "content", systemPrompt),
            java.util.Map.of("role", "user", "content", question)
        );

        Map<String, Object> request = Map.of(
                "messages", messages,
                "max_tokens", 1600,
                "temperature", 0.7,
                "top_p", 0.95,
                "frequency_penalty", 0,
                "presence_penalty", 0
        );

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(request, headers);

        String url = String.format("%s/openai/deployments/%s/chat/completions?api-version=2025-01-01-preview",
                azureOpenAiUrl, deploymentName);

        log.info("[requestId={}] AiRouterService.callAzureOpenAiWithContext request: url={}", requestId, url);
        ResponseEntity<Map> response = metrics.timeLlm("azure_openai", deploymentName, "chat_completion_rag",
                () -> restTemplate.exchange(url, HttpMethod.POST, entity, Map.class));
        log.debug("[requestId={}] AiRouterService.callAzureOpenAiWithContext response received", requestId);
        return extractChatCompletionText(response.getBody());
    }

    private String extractChatCompletionText(Map responseBody) {
        if (responseBody == null) return null;
        var choices = (java.util.List<Map<String, Object>>) responseBody.get("choices");
        if (choices != null && !choices.isEmpty()) {
            Map<String, Object> firstChoice = choices.get(0);
            Map<String, Object> message = (Map<String, Object>) firstChoice.get("message");
            if (message != null) {
                Object content = message.get("content");
                if (content != null) return content.toString();
            }
        }
        return null;
    }
}