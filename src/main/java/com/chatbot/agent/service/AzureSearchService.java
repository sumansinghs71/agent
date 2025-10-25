package com.chatbot.agent.service;

import com.azure.core.credential.AzureKeyCredential;
import com.azure.search.documents.SearchClient;
import com.azure.search.documents.SearchClientBuilder;
import com.azure.search.documents.indexes.models.IndexDocumentsBatch;
import com.azure.search.documents.models.IndexDocumentsResult;
import com.azure.search.documents.models.SearchOptions;
import com.azure.search.documents.models.SearchResult;
import com.chatbot.agent.exception.AzureSearchException;
import com.chatbot.agent.model.CitationModel;
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

    /**
     * Upload chunks with metadata to Azure Search
     */
    public void uploadChunksWithMetadata(Long chatbotId, Long documentId,
                                         List<DocumentService.ChunkWithMetadata> chunksWithMetadata) {
        String requestId = MDC.get("requestId");
        log.info("[requestId={}] Uploading {} chunks with metadata to Azure Search",
                requestId, chunksWithMetadata.size());

        List<Map<String, Object>> docs = new ArrayList<>();
        for (int i = 0; i < chunksWithMetadata.size(); i++) {
            DocumentService.ChunkWithMetadata chunk = chunksWithMetadata.get(i);

            Map<String, Object> doc = new HashMap<>();
            doc.put("id", chatbotId + "-" + documentId + "-" + i);
            doc.put("chatbot_id", chatbotId);
            doc.put("document_id", documentId);
            doc.put("chunk_index", i);
            doc.put("chunk_text", chunk.chunkText);
            doc.put("document_name", chunk.documentName);
            doc.put("page_number", chunk.pageNumber != null ? chunk.pageNumber : 0);
            doc.put("section_title", chunk.sectionTitle != null ? chunk.sectionTitle : "");
            doc.put("chunk_start_pos", chunk.chunkStartPos != null ? chunk.chunkStartPos : 0);
            doc.put("chunk_end_pos", chunk.chunkEndPos != null ? chunk.chunkEndPos : 0);

            docs.add(doc);
        }

        IndexDocumentsBatch<Map<String, Object>> batch = new IndexDocumentsBatch<>();
        batch.addUploadActions(docs);

        try {
            IndexDocumentsResult result = searchClient.indexDocuments(batch);
            log.info("[requestId={}] Indexed {} chunks with metadata to Azure Search",
                    requestId, docs.size());
        } catch (Exception e) {
            log.error("[requestId={}] Failed to upload chunks with metadata", requestId, e);
            throw new AzureSearchException("Failed to upload chunks with metadata", e);
        }
    }

    /**
     * Search and return chunks with metadata
     */
    public List<CitationModel.ChunkWithMetadata> searchChunksWithMetadata(Long chatbotId,
                                                                          String question,
                                                                          int topK) {
        String requestId = MDC.get("requestId");
        log.info("[requestId={}] Searching Azure Search with metadata: topK={}", requestId, topK);

        String filter = String.format("chatbot_id eq %d", chatbotId);
        SearchOptions options = new SearchOptions()
                .setFilter(filter)
                .setTop(topK)
                // Only select fields that are guaranteed to exist in the index. Some deployments
                // may not have chunk_start_pos / chunk_end_pos in the index schema which will
                // cause Azure Search to return 400. Remove those fields from the select to be
                // defensive. If you control the index, add those fields to the index schema.
                // "section_title",
                .setSelect("chunk_text", "document_name", "page_number", "section_title",
                        "document_id", "chunk_index");

        List<CitationModel.ChunkWithMetadata> chunks = new ArrayList<>();

        try {
            Iterable<SearchResult> results = searchClient.search(question, options, null);
            int rank = 0;

            for (SearchResult result : results) {
                Map<String, Object> doc = result.getDocument(Map.class);

                CitationModel.ChunkWithMetadata chunk = new CitationModel.ChunkWithMetadata();
                chunk.setChunkText((String) doc.get("chunk_text"));
                chunk.setDocumentName((String) doc.get("document_name"));

                // Defensive numeric parsing: Azure may return Integer/Long/Double depending on index/schema
                Object pn = doc.get("page_number");
                if (pn instanceof Number) {
                    chunk.setPageNumber(((Number) pn).intValue());
                } else if (pn != null) {
                    try { chunk.setPageNumber(Integer.parseInt(pn.toString())); } catch (NumberFormatException ignored) {}
                }

                Object section = doc.get("section_title");
                if (section != null) chunk.setSectionTitle(section.toString());

                Object did = doc.get("document_id");
                if (did instanceof Number) {
                    chunk.setDocumentId(((Number) did).longValue());
                } else if (did != null) {
                    try { chunk.setDocumentId(Long.parseLong(did.toString())); } catch (NumberFormatException ignored) {}
                }

                Object ci = doc.get("chunk_index");
                if (ci instanceof Number) {
                    chunk.setChunkIndex(((Number) ci).intValue());
                } else if (ci != null) {
                    try { chunk.setChunkIndex(Integer.parseInt(ci.toString())); } catch (NumberFormatException ignored) {}
                }
                 // chunk_start_pos / chunk_end_pos may not exist in the index schema; attempt
                 // to read them defensively if present.
                 if (doc.get("chunk_start_pos") != null) {
                     try {
                         chunk.setChunkStartPos(((Number) doc.get("chunk_start_pos")).intValue());
                     } catch (ClassCastException ex) {
                         // ignore - field not present in expected type
                     }
                 }
                 if (doc.get("chunk_end_pos") != null) {
                     try {
                         chunk.setChunkEndPos(((Number) doc.get("chunk_end_pos")).intValue());
                     } catch (ClassCastException ex) {
                         // ignore
                     }
                 }

                  // Azure Search score (0-1, higher is better)
                  Double score = result.getScore();
                  chunk.setSimilarityScore(score != null ? score.floatValue() : 0.0f);

                // Add the constructed chunk to the result list
                chunks.add(chunk);

             }

            log.info("[requestId={}] Retrieved {} chunks with metadata from Azure Search",
                    requestId, chunks.size());

        } catch (Exception e) {
            log.error("[requestId={}] Azure Search with metadata failed", requestId, e);
            throw new AzureSearchException("Failed to search Azure Search with metadata", e);
        }

        return chunks;
    }
}
