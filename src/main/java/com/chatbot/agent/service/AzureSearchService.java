package com.chatbot.agent.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;

@Service
public class AzureSearchService {
    private static final Logger log = LoggerFactory.getLogger(AzureSearchService.class);

    @Value("${azure.openai.search-endpoint}")
    private String searchEndpoint;

    @Value("${azure.openai.search-key}")
    private String searchKey;

    @Value("${azure.openai.search-index}")
    private String searchIndex;

    private final RestTemplate restTemplate;

    public AzureSearchService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public void uploadChunksToAzureSearch(Long chatbotId, Long documentId, List<String> chunks) {
        // Use correct endpoint and API version for Azure Search chat API
        String url = String.format("%s/indexes/%s/docs/search.index?api-version=2023-11-01", searchEndpoint, searchIndex);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("api-key", searchKey);

        List<Map<String, Object>> actions = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            Map<String, Object> doc = new HashMap<>();
            doc.put("@search.action", "upload");
            doc.put("chatbot_id", chatbotId);
            doc.put("document_id", documentId);
            doc.put("chunk_index", i);
            doc.put("chunk_text", chunks.get(i));
            doc.put("id", chatbotId + "-" + documentId + "-" + i);
            actions.add(doc);
        }
        Map<String, Object> payload = Map.of("value", actions);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(payload, headers);
        try {
            ResponseEntity<Map> response = restTemplate.postForEntity(url, entity, Map.class);
            if (!response.getStatusCode().is2xxSuccessful()) {
                log.error("Failed to upload to Azure Search: {}", response.getBody());
                throw new RuntimeException("Azure Search indexing failed");
            }
        } catch (Exception e) {
            log.error("Exception uploading to Azure Search", e);
            throw new RuntimeException("Azure Search indexing exception", e);
        }
    }

    public List<String> searchRelevantChunks(Long chatbotId, String question, int topK) {
        String url = String.format("%s/indexes/%s/docs/search?api-version=2023-11-01&$top=%d", searchEndpoint, searchIndex, topK);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("api-key", searchKey);

        Map<String, Object> payload = new HashMap<>();
        payload.put("search", question);
        payload.put("filter", String.format("chatbot_id eq %d", chatbotId));
        payload.put("select", "chunk_text");
        // Removed semantic search parameters for compatibility with Free tier

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(payload, headers);
        try {
            ResponseEntity<Map> response = restTemplate.postForEntity(url, entity, Map.class);
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                log.error("Failed to search Azure Search: {}", response.getBody());
                return Collections.emptyList();
            }
            List<Map<String, Object>> results = (List<Map<String, Object>>) response.getBody().get("value");
            List<String> chunks = new ArrayList<>();
            if (results != null) {
                for (Map<String, Object> doc : results) {
                    Object chunk = doc.get("chunk_text");
                    if (chunk != null) chunks.add(chunk.toString());
                }
            }
            return chunks;
        } catch (Exception e) {
            log.error("Exception searching Azure Search", e);
            return Collections.emptyList();
        }
    }
}
