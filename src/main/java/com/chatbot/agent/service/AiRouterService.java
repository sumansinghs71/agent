package com.chatbot.agent.service;


import com.chatbot.agent.model.Model;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

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

    public AiRouterService(@Qualifier("restTemplate") RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public String routeToAi(Model.ModelType modelType, String prompt) {
        if (modelType == Model.ModelType.AZURE_OPENAI) {
            return callAzureOpenAiWithRetry(prompt, 2);
        } else {
            return callLlama(prompt);
        }
    }

    private String callAzureOpenAiWithRetry(String prompt, int maxRetries) {
        int retries = 0;
        while (retries < maxRetries) {
            try {
                return callAzureOpenAi(prompt);
            } catch (HttpClientErrorException.TooManyRequests e) {
                // Wait and retry
                try {
                    Thread.sleep(60000); // Wait 60 seconds
                } catch (InterruptedException ignored) {}
                retries++;
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

        log.info("[requestId={}] AiRouterService.callAzureOpenAi request: url={}, body={}", requestId, url, request);
        ResponseEntity<Map> response = restTemplate.exchange(
                url, HttpMethod.POST, entity, Map.class);
        log.info("[requestId={}] AiRouterService.callAzureOpenAi response: {}", requestId, response.getBody());
        return extractChatCompletionText(response.getBody());
    }

    private String callLlama(String prompt) {
        String requestId = MDC.get("requestId");
        String url = llamaUrl + "/generate";
        String requestBody = String.format("{\"text\": \"%s\"}", prompt);
        log.info("[requestId={}] AiRouterService.callLlama request: url={}, body={}", requestId, url, requestBody);
        String response = restTemplate.postForObject(url, requestBody, String.class);
        log.info("[requestId={}] AiRouterService.callLlama response: {}", requestId, response);
        return response;
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

        log.info("[requestId={}] AiRouterService.callAzureOpenAiWithContext request: url={}, body={}", requestId, url, request);
        ResponseEntity<Map> response = restTemplate.exchange(
                url, HttpMethod.POST, entity, Map.class);
        log.info("[requestId={}] AiRouterService.callAzureOpenAiWithContext response: {}", requestId, response.getBody());
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