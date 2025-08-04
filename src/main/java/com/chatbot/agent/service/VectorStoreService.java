package com.chatbot.agent.service;

import org.postgresql.util.PGobject;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Service
public class VectorStoreService {


    private final JdbcTemplate vectorJdbcTemplate;
    private final OllamaService ollamaService;

    public VectorStoreService(@Qualifier("pgVectorJdbcTemplate") JdbcTemplate vectorJdbcTemplate,
                              OllamaService ollamaService) {
        this.vectorJdbcTemplate = vectorJdbcTemplate;
        this.ollamaService = ollamaService;
    }

    public void indexDocument(Long chatbotId, Long documentId, List<String> chunks) {
        indexToPgVector(chatbotId, documentId, chunks);
    }

    public void indexSingleChunk(Long chatbotId, Long documentId, int chunkIndex, String chunk) {
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
    }

    private void indexToPgVector(Long chatbotId, Long documentId, List<String> chunks) {
        String sql = "INSERT INTO document_vectors (chatbot_id, document_id, chunk_index, chunk_text, embedding) VALUES (?, ?, ?, ?, ?)";

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
    }


    private PGobject toPGVector(float[] embedding) {
        PGobject pgObject = new PGobject();
        pgObject.setType("vector");
        String vectorStr = "[" + arrayToCommaDelimited(embedding) + "]";
        try {
            pgObject.setValue(vectorStr);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to set PGobject value for vector", e);
        }
        return pgObject;
    }

    private String arrayToCommaDelimited(float[] array) {
        return IntStream.range(0, array.length)
                .mapToObj(i -> Float.toString(array[i]))
                .collect(Collectors.joining(","));
    }

    public String searchAndGenerateResponse(Long chatbotId, String question) {
        // 1. Generate embedding for the question
        float[] questionEmbedding = ollamaService.generateEmbedding(question);

        // 2. Convert to PostgreSQL vector string representation
        String vectorString = toVectorString(questionEmbedding);

        // 3. Create SQL query with proper vector syntax
        String contextSql = "SELECT chunk_text " +
                "FROM document_vectors " +
                "WHERE chatbot_id = ? " +
                "ORDER BY embedding <=> ? " +  // Vector comparison operator
                "LIMIT 5";

        // 4. Execute query with proper parameter types
        List<String> contextChunks = vectorJdbcTemplate.queryForList(
                contextSql,
                new Object[]{chatbotId, vectorString},
                new int[]{Types.BIGINT, Types.OTHER},  // Explicit type specification
                String.class
        );

        // 5. Build context
        StringBuilder context = new StringBuilder();
        for (String chunk : contextChunks) {
            context.append(chunk).append("\n\n");
        }

        // 6. Generate response
        return ollamaService.generateResponse(question, context.toString());
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
}
