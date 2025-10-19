package com.chatbot.agent.service;

import com.chatbot.agent.exception.VectorStoreException;
import org.postgresql.util.PGobject;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

@Service
public class VectorStoreService {


    private final JdbcTemplate vectorJdbcTemplate;
    private final OllamaService ollamaService;

    private static final Logger log = LoggerFactory.getLogger(VectorStoreService.class);

    public VectorStoreService(@Qualifier("pgVectorJdbcTemplate") JdbcTemplate vectorJdbcTemplate,
                              OllamaService ollamaService) {
        this.vectorJdbcTemplate = vectorJdbcTemplate;
        this.ollamaService = ollamaService;
    }

    public void indexDocument(Long chatbotId, Long documentId, List<String> chunks) {
        String requestId = MDC.get("requestId");
        log.info("[requestId={}] VectorStoreService.indexDocument request: chatbotId={}, documentId={}, chunks.size={}", requestId, chatbotId, documentId, chunks != null ? chunks.size() : 0);
        try {
            indexToPgVector(chatbotId, documentId, chunks);
            log.info("[requestId={}] VectorStoreService.indexDocument success: chatbotId={}, documentId={}", requestId, chatbotId, documentId);
        } catch (Exception e) {
            log.error("[requestId={}] Failed to index document: chatbotId={}, documentId={}, error={}", requestId, chatbotId, documentId, e.getMessage(), e);
            throw new VectorStoreException("Failed to index document to vector store", e);
        }
    }

    public void indexSingleChunk(Long chatbotId, Long documentId, int chunkIndex, String chunk) {
        String requestId = MDC.get("requestId");
        log.info("[requestId={}] VectorStoreService.indexSingleChunk request: chatbotId={}, documentId={}, chunkIndex={}, chunk.length={}", requestId, chatbotId, documentId, chunkIndex, chunk != null ? chunk.length() : 0);
        try {
            float[] embedding = ollamaService.generateEmbedding(chunk);
            PGobject pgVector = toPGVector(embedding);
            String sql = "INSERT INTO document_vectors (chatbot_id, document_id, chunk_index, chunk_text, embedding) VALUES (?, ?, ?, ?, ?)";
            vectorJdbcTemplate.update(sql, ps -> {
                ps.setLong(1, chatbotId);
                ps.setLong(2, documentId);
                ps.setInt(3, chunkIndex);
                ps.setString(4, chunk);
                ps.setObject(5, pgVector);
            });
            log.info("[requestId={}] VectorStoreService.indexSingleChunk success: chatbotId={}, documentId={}, chunkIndex={}", requestId, chatbotId, documentId, chunkIndex);
        } catch (Exception e) {
            log.error("[requestId={}] Failed to index single chunk: chatbotId={}, documentId={}, chunkIndex={}, error={}", requestId, chatbotId, documentId, chunkIndex, e.getMessage(), e);
            throw new VectorStoreException("Failed to index single chunk to vector store", e);
        }
    }

    private void indexToPgVector(Long chatbotId, Long documentId, List<String> chunks) {
        String sql = "INSERT INTO document_vectors (chatbot_id, document_id, chunk_index, chunk_text, embedding) VALUES (?, ?, ?, ?, ?)";
        try {
            // Precompute embeddings and PGobjects
            List<float[]> embeddings = chunks.stream()
                    .map(ollamaService::generateEmbedding)
                    .collect(Collectors.toList());
            List<PGobject> pgVectors = embeddings.stream()
                    .map(this::toPGVector)
                    .collect(Collectors.toList());
            vectorJdbcTemplate.batchUpdate(sql, new org.springframework.jdbc.core.BatchPreparedStatementSetter() {
                @Override
                public void setValues(java.sql.PreparedStatement ps, int i) throws SQLException {
                    ps.setLong(1, chatbotId);
                    ps.setLong(2, documentId);
                    ps.setInt(3, i);
                    ps.setString(4, chunks.get(i));
                    ps.setObject(5, pgVectors.get(i));
                }
                @Override
                public int getBatchSize() {
                    return chunks.size();
                }
            });
        } catch (Exception e) {
            String requestId = MDC.get("requestId");
            log.error("[requestId={}] Batch index failed: chatbotId={}, documentId={}, error={}", requestId, chatbotId, documentId, e.getMessage(), e);
            throw new VectorStoreException("Failed to batch index to vector store", e);
        }
    }

    private PGobject toPGVector(float[] embedding) {
        PGobject pgObject = new PGobject();
        pgObject.setType("vector");
        String vectorStr = "[" + arrayToCommaDelimited(embedding) + "]";
        try {
            pgObject.setValue(vectorStr);
        } catch (SQLException e) {
            String requestId = MDC.get("requestId");
            log.error("[requestId={}] Failed to set PGobject value for vector: error={}", requestId, e.getMessage(), e);
            throw new VectorStoreException("Failed to set PGobject value for vector", e);
        }
        return pgObject;
    }


    public String searchAndGenerateResponse(Long chatbotId, String question,  String systemInstruction, String userInstruction) {
        String requestId = MDC.get("requestId");
        log.info("[requestId={}] VectorStoreService.searchAndGenerateResponse request: chatbotId={}, question={}", requestId, chatbotId, question);
        try {
            // 1. Generate embedding for question
            float[] questionEmbedding = ollamaService.generateEmbedding(question);

            // 2. Search for relevant chunks
            String vectorString = toVectorString(questionEmbedding);
            String contextSql = "SELECT chunk_text " +
                    "FROM document_vectors " +
                    "WHERE chatbot_id = ? " +
                    "ORDER BY embedding <=> ? " +
                    "LIMIT 5";

            List<String> contextChunks = vectorJdbcTemplate.queryForList(
                    contextSql,
                    new Object[]{chatbotId, vectorString},
                    new int[]{Types.BIGINT, Types.OTHER},
                    String.class
            );

            // 3. Build context with instructions
            StringBuilder context = new StringBuilder();

            // ADD SYSTEM INSTRUCTION
            if (systemInstruction != null && !systemInstruction.isEmpty()) {
                context.append("=== SYSTEM INSTRUCTIONS ===\n");
                context.append(systemInstruction).append("\n\n");
            }

            // ADD USER INSTRUCTION
            if (userInstruction != null && !userInstruction.isEmpty()) {
                context.append("=== USER INSTRUCTIONS ===\n");
                context.append(userInstruction).append("\n\n");
            }

            // ADD DOCUMENT CHUNKS
            context.append("=== DOCUMENT CONTEXT ===\n");
            for (String chunk : contextChunks) {
                context.append(chunk).append("\n\n");
            }

            // 4. Generate response with instructions
            String response = ollamaService.generateResponse(question, context.toString());

            log.info("[requestId={}] Response generated", requestId);
            return response;

        } catch (Exception e) {
            log.error("[requestId={}] Failed to search and generate response: chatbotId={}, error={}", requestId, chatbotId, e.getMessage(), e);
            throw new VectorStoreException("Failed to search and generate response from vector store", e);
        }
    }

    private String toVectorString(float[] vector) {
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(vector[i]);
        }
        sb.append("]");
        return sb.toString();
    }

    private String arrayToCommaDelimited(float[] array) {
        return IntStream.range(0, array.length)
                .mapToObj(i -> Float.toString(array[i]))
                .collect(Collectors.joining(","));
    }
}
