package com.chatbot.agent.repository;


import com.chatbot.agent.model.Model;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public class ChatbotRepository {

    private final JdbcTemplate jdbcTemplate;

    public ChatbotRepository(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<Model.Chatbot> findById(Long id) {
        String sql = "SELECT * FROM chatbot WHERE id = ?";
        return jdbcTemplate.query(sql, chatbotRowMapper, id).stream().findFirst();
    }

    public List<Model.Chatbot> findAll() {
        String sql = "SELECT * FROM chatbot";
        return jdbcTemplate.query(sql, chatbotRowMapper);
    }

    public void delete(Long id) {
        String sql = "DELETE FROM chatbot WHERE id = ?";
        jdbcTemplate.update(sql, id);
    }

    private final RowMapper<Model.Chatbot> chatbotRowMapper = (rs, rowNum) -> {
        Model.Chatbot chatbot = new Model.Chatbot();
        chatbot.setId(rs.getLong("id"));
        chatbot.setName(rs.getString("name"));
        chatbot.setModelType(Model.ModelType.valueOf(rs.getString("model_type")));
        chatbot.setSystemInstruction(rs.getString("system_instruction"));
        chatbot.setUserInstruction(rs.getString("user_instruction"));
        chatbot.setInstructionEnabled(rs.getBoolean("instruction_enabled"));
        chatbot.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
        return chatbot;
    };

    public void save(Model.Chatbot chatbot) {
        String sql = "INSERT INTO chatbot (name, model_type, system_instruction, user_instruction, instruction_enabled) " +
                "VALUES (?, ?, ?, ?, ?)";
        jdbcTemplate.update(sql,
                chatbot.getName(),
                chatbot.getModelType().name(),
                chatbot.getSystemInstruction(),
                chatbot.getUserInstruction(),
                chatbot.getInstructionEnabled() != null ? chatbot.getInstructionEnabled() : true
        );
    }

    public void update(Model.Chatbot chatbot) {
        String sql = "UPDATE chatbot SET name = ?, model_type = ?, system_instruction = ?, " +
                "user_instruction = ?, instruction_enabled = ? WHERE id = ?";
        jdbcTemplate.update(sql,
                chatbot.getName(),
                chatbot.getModelType().name(),
                chatbot.getSystemInstruction(),
                chatbot.getUserInstruction(),
                chatbot.getInstructionEnabled(),
                chatbot.getId()
        );
    }
}