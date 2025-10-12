package com.chatbot.agent.service;

import com.azure.core.credential.AzureKeyCredential;
import com.azure.search.documents.SearchClient;
import com.azure.search.documents.SearchClientBuilder;
import com.azure.search.documents.indexes.models.IndexDocumentsBatch;
import com.azure.search.documents.models.IndexDocumentsResult;
import com.azure.search.documents.models.SearchOptions;
import com.azure.search.documents.models.SearchResult;
import com.chatbot.agent.exception.AzureSearchException;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;

@Service
public class AzureSearchService {
    private static final Logger log = LoggerFactory.getLogger(AzureSearchService.class);
    private static final String SYSTEM_PROMPT = "You are an intelligent bot for resume handling. Answer user questions based on the provided context. If the answer is not in the context, say you don't know.";

    @Value("${azure.openai.search-endpoint}")
    private String searchEndpoint;

    @Value("${azure.openai.search-key}")
    private String searchKey;

    @Value("${azure.openai.search-index}")
    private String searchIndex;

    private final RestTemplate restTemplate;
    private SearchClient searchClient;

    public AzureSearchService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @PostConstruct
    private void init() {
        this.searchClient = new SearchClientBuilder()
                .endpoint(searchEndpoint)
                .credential(new AzureKeyCredential(searchKey))
                .indexName(searchIndex)
                .buildClient();
    }

    public void uploadChunksToAzureSearch(Long chatbotId, Long documentId, List<String> chunks) {
        String requestId = MDC.get("requestId");
        List<Map<String, Object>> docs = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            Map<String, Object> doc = new HashMap<>();
            doc.put("id", chatbotId + "-" + documentId + "-" + i);
            doc.put("chatbot_id", chatbotId);
            doc.put("document_id", documentId);
            doc.put("chunk_index", i);
            doc.put("chunk_text", chunks.get(i));
            docs.add(doc);
        }
        IndexDocumentsBatch<Map<String, Object>> batch = new IndexDocumentsBatch<>();
        batch.addUploadActions(docs);
        log.info("[requestId={}] AzureSearchService.uploadChunksToAzureSearch request: {}", requestId, docs);
        try {
            IndexDocumentsResult result = searchClient.indexDocuments(batch);
            log.info("[requestId={}] AzureSearchService.uploadChunksToAzureSearch response: {}", requestId, result);
            log.info("[requestId={}] Indexed {} chunks to Azure Search", requestId, docs.size());
        } catch (Exception e) {
            log.error("[requestId={}] Exception uploading to Azure Search", requestId, e);
            throw new AzureSearchException("Failed to upload chunks to Azure Search", e);
        }
    }

    public List<String> searchRelevantChunks(Long chatbotId, String question, int topK) {
        String requestId = MDC.get("requestId");
        String filter = String.format("chatbot_id eq %d", chatbotId);
        SearchOptions options = new SearchOptions()
                .setFilter(filter)
                .setTop(topK)
                .setSelect("chunk_text");
        log.info("[requestId={}] AzureSearchService.searchRelevantChunks request: question={}, options={}", requestId, question, options);
        List<String> chunks = new ArrayList<>();
        try {
            Iterable<SearchResult> results = searchClient.search(question, options, null);
            for (SearchResult result : results) {
                Map<String, Object> doc = result.getDocument(Map.class);
                Object chunk = doc.get("chunk_text");
                if (chunk != null) chunks.add(chunk.toString());
            }
            log.info("[requestId={}] AzureSearchService.searchRelevantChunks response: {}", requestId, chunks);
            log.info("[requestId={}] Retrieved {} chunks from Azure Search", requestId, chunks.size());
        } catch (Exception e) {
            log.error("[requestId={}] Exception searching Azure Search", requestId, e);
            throw new AzureSearchException("Failed to search Azure Search", e);
        }
        return chunks;
    }
}
