package com.chatbot.agent.repository;

import com.chatbot.agent.model.Model;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

@Repository
public class DocumentRepository {

    private final JdbcTemplate jdbcTemplate;

    public DocumentRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private final RowMapper<Model.Document> documentRowMapper = (rs, rowNum) -> {
        Model.Document document = new Model.Document();
        document.setId(rs.getLong("id"));
        document.setChatbotId(rs.getLong("chatbot_id"));
        document.setFileName(rs.getString("file_name"));
        document.setFilePath(rs.getString("file_path"));
        document.setStatus(Model.DocumentStatus.valueOf(rs.getString("status")));
        document.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
        return document;
    };

    public Model.Document save(Model.Document document) {
        if (document.getId() == null) {
            // Insert new document and get generated ID
            String sql = "INSERT INTO document (chatbot_id, file_name, file_path, status) VALUES (?, ?, ?, ?)";

            KeyHolder keyHolder = new GeneratedKeyHolder();

            jdbcTemplate.update(connection -> {
                PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
                ps.setLong(1, document.getChatbotId());
                ps.setString(2, document.getFileName());
                ps.setString(3, document.getFilePath());
                ps.setString(4, document.getStatus().name());
                return ps;
            }, keyHolder);

            Number key = keyHolder.getKey();
            if (key != null) {
                document.setId(key.longValue());
            } else {
                throw new IllegalStateException("Failed to retrieve generated ID after insert");
            }

        } else {
            // Update existing document
            String sql = "UPDATE document SET file_name = ?, file_path = ?, status = ? WHERE id = ?";
            jdbcTemplate.update(sql,
                    document.getFileName(),
                    document.getFilePath(),
                    document.getStatus().name(),
                    document.getId());
        }
        return document;
    }
    public void updateStatus(Long documentId, Model.DocumentStatus status) {
        String sql = "UPDATE document SET status = ? WHERE id = ?";
        jdbcTemplate.update(sql, status.name(), documentId);
    }

    public Optional<Model.Document> findById(Long id) {
        String sql = "SELECT * FROM document WHERE id = ?";
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(sql, documentRowMapper, id));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public List<Model.Document> findByChatbotId(Long chatbotId) {
        String sql = "SELECT * FROM document WHERE chatbot_id = ?";
        return jdbcTemplate.query(sql, documentRowMapper, chatbotId);
    }
}