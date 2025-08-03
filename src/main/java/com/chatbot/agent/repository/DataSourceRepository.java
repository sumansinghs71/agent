package com.chatbot.agent.repository;

import com.chatbot.agent.model.Model;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class DataSourceRepository {

    private final JdbcTemplate jdbcTemplate;

    public DataSourceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private final RowMapper<Model.DataSource> dataSourceRowMapper = (rs, rowNum) -> {
        Model.DataSource dataSource = new Model.DataSource();
        dataSource.setId(rs.getLong("id"));
        dataSource.setChatbotId(rs.getLong("chatbot_id"));
        dataSource.setSourceType(Model.SourceType.valueOf(rs.getString("source_type")));
        dataSource.setConfig(rs.getString("config"));
        return dataSource;
    };

    public void save(Model.DataSource dataSource) {
        if (dataSource.getId() == null) {
            // Insert new
            String sql = "INSERT INTO data_source (chatbot_id, source_type, config) VALUES (?, ?, ?)";
            jdbcTemplate.update(sql,
                    dataSource.getChatbotId(),
                    dataSource.getSourceType().name(),
                    dataSource.getConfig());
        } else {
            // Update existing
            String sql = "UPDATE data_source SET chatbot_id = ?, source_type = ?, config = ? WHERE id = ?";
            jdbcTemplate.update(sql,
                    dataSource.getChatbotId(),
                    dataSource.getSourceType().name(),
                    dataSource.getConfig(),
                    dataSource.getId());
        }
    }

    public List<Model.DataSource> findByChatbotId(Long chatbotId) {
        String sql = "SELECT * FROM data_source WHERE chatbot_id = ?";
        return jdbcTemplate.query(sql, dataSourceRowMapper, chatbotId);
    }

    public void delete(Long id) {
        String sql = "DELETE FROM data_source WHERE id = ?";
        jdbcTemplate.update(sql, id);
    }

    public void deleteByChatbotId(Long chatbotId) {
        String sql = "DELETE FROM data_source WHERE chatbot_id = ?";
        jdbcTemplate.update(sql, chatbotId);
    }
}