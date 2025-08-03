package com.chatbot.agent.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class OllamaService {

    @Value("${llama.base-url}")
    private String baseUrl;

    @Value("${llama.embedding-model}")
    private String embeddingModel;

    @Value("${llama.generation-model}")
    private String generationModel;


    private final RestTemplate restTemplate;

    public OllamaService(@Qualifier("restTemplate") RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class EmbeddingRequest {
        private String model;
        private String prompt;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class EmbeddingResponse {
        @JsonProperty("embedding")
        private List<Double> embedding;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class GenerateRequest {
        private String model;
        private String prompt;
        private List<Integer> context;
        private boolean stream = false;
        private Map<String, Object> options;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class GenerateResponse {
        @JsonProperty("response")
        private String response;
        @JsonProperty("context")
        private List<Integer> context;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class TokenizeResponse {
        private List<Integer> tokens;
    }

    public float[] generateEmbedding(String text) {
        String url = baseUrl + "/api/embeddings";

        EmbeddingRequest request = new EmbeddingRequest();
        request.setModel(embeddingModel);
        request.setPrompt(text);

        try {
            EmbeddingResponse response = restTemplate.postForObject(url, request, EmbeddingResponse.class);
            if (response != null && response.getEmbedding() != null) {
                float[] embedding = new float[response.getEmbedding().size()];
                for (int i = 0; i < response.getEmbedding().size(); i++) {
                    embedding[i] = response.getEmbedding().get(i).floatValue();
                }
                return embedding;
            }
        } catch (Exception e) {
            // log.error("Failed to generate embedding: {}", e.getMessage());
        }
        throw new RuntimeException("Failed to generate embedding");
    }

    public String generateResponse(String prompt, String contextText) {
        try {
            // 1. Truncate context to last 15,000 characters
            if (contextText.length() > 15000) {
                contextText = contextText.substring(contextText.length() - 15000);
            }

            // 2. Create the full prompt with context
            String fullPrompt = "Context: " + contextText + "\n\nQuestion: " + prompt + "\n\nAnswer:";

            // 3. Build request with timeout options
            Map<String, Object> options = new HashMap<>();
            options.put("num_ctx", 4096);  // Full context window
            options.put("temperature", 0.7);
            options.put("num_predict", 300);
            options.put("timeout", 30000);

            Map<String, Object> request = new HashMap<>();
            request.put("model", generationModel);
            request.put("prompt", fullPrompt);
            request.put("stream", false);
            request.put("options", options);

            // 4. Create HTTP entity
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(request, headers);

            // 5. Send request
            String url = baseUrl + "/api/generate";
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    entity,
                    new ParameterizedTypeReference<Map<String, Object>>() {},
                    Timeout.of(Duration.ofSeconds(30))
            );

            // 6. Process response
            if (response.getStatusCode().is2xxSuccessful()) {
                Map<String, Object> body = response.getBody();
                if (body != null && body.containsKey("response")) {
                    return ((String) body.get("response")).trim();
                }
            }
        } catch (ResourceAccessException e) {
            return "Request timed out. Please try again.";
        } catch (Exception e) {
            // Log error
        }
        return "I couldn't generate a response. Please try again later.";
    }

    private List<Integer> tokenizeText(String text) {
        try {
            String tokenizeUrl = baseUrl + "/api/tokenize";
            Map<String, String> request = Map.of("content", text);

            TokenizeResponse response = restTemplate.postForObject(
                    tokenizeUrl, request, TokenizeResponse.class);

            return response != null ? response.getTokens() : Collections.emptyList();
        } catch (Exception e) {
            // log.warn("Failed to tokenize text: {}", e.getMessage());
            return Collections.emptyList();
        }
    }
}
