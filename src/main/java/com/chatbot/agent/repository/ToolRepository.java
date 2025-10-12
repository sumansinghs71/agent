package com.chatbot.agent.repository;

import com.chatbot.agent.model.ToolModel;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class ToolRepository {

    private JdbcTemplate jdbcTemplate;
    private ObjectMapper objectMapper;

    public ToolRepository(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbcTemplate,
                          ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    private final RowMapper<ToolModel.Tool> toolRowMapper = (rs, rowNum) -> {
        ToolModel.Tool tool = new ToolModel.Tool();
        tool.setId(rs.getLong("id"));
        tool.setChatbotId(rs.getLong("chatbot_id"));
        tool.setFuncNameKey(rs.getString("func_name_key"));
        tool.setLabel(rs.getString("label"));
        tool.setPrompt(rs.getString("prompt"));

        // Parse JSON params
        String paramsJson = rs.getString("params");
        if (paramsJson != null) {
            try {
                List<ToolModel.ToolParameter> params = objectMapper.readValue(
                        paramsJson,
                        new TypeReference<List<ToolModel.ToolParameter>>() {}
                );
                tool.setParams(params);
            } catch (JsonProcessingException e) {
                throw new RuntimeException("Failed to parse tool params JSON", e);
            }
        }

        tool.setFunctionType(ToolModel.FunctionType.valueOf(rs.getString("function_type")));
        tool.setDataSource(rs.getString("data_source"));
        tool.setSqlQuery(rs.getString("sql_query"));
        tool.setHttpMethod(rs.getString("http_method"));
        tool.setHttpPath(rs.getString("http_path"));

        // Parse JSON headers
        String headersJson = rs.getString("http_headers");
        if (headersJson != null) {
            try {
                Map<String, String> headers = objectMapper.readValue(
                        headersJson,
                        new TypeReference<Map<String, String>>() {}
                );
                tool.setHttpHeaders(headers);
            } catch (JsonProcessingException e) {
                throw new RuntimeException("Failed to parse HTTP headers JSON", e);
            }
        }

        tool.setHttpBody(rs.getString("http_body"));
        tool.setTimeout(rs.getInt("timeout"));
        tool.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
        tool.setUpdatedAt(rs.getTimestamp("updated_at").toLocalDateTime());

        return tool;
    };

    public ToolModel.Tool save(ToolModel.Tool tool) {
        if (tool.getId() == null) {
            return insert(tool);
        } else {
            update(tool);
            return tool;
        }
    }

    private ToolModel.Tool insert(ToolModel.Tool tool) {
        String sql = "INSERT INTO tool (chatbot_id, func_name_key, label, prompt, params, " +
                "function_type, data_source, sql_query, http_method, http_path, " +
                "http_headers, http_body, timeout) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

        KeyHolder keyHolder = new GeneratedKeyHolder();

        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, tool.getChatbotId());
            ps.setString(2, tool.getFuncNameKey());
            ps.setString(3, tool.getLabel());
            ps.setString(4, tool.getPrompt());
            ps.setString(5, toJson(tool.getParams()));
            ps.setString(6, tool.getFunctionType().name());
            ps.setString(7, tool.getDataSource());
            ps.setString(8, tool.getSqlQuery());
            ps.setString(9, tool.getHttpMethod());
            ps.setString(10, tool.getHttpPath());
            ps.setString(11, toJson(tool.getHttpHeaders()));
            ps.setString(12, tool.getHttpBody());
            ps.setInt(13, tool.getTimeout() != null ? tool.getTimeout() : 30000);
            return ps;
        }, keyHolder);

        Number key = keyHolder.getKey();
        if (key != null) {
            tool.setId(key.longValue());
        }
        return tool;
    }

    private void update(ToolModel.Tool tool) {
        String sql = "UPDATE tool SET label = ?, prompt = ?, params = ?, " +
                "function_type = ?, data_source = ?, sql_query = ?, " +
                "http_method = ?, http_path = ?, http_headers = ?, " +
                "http_body = ?, timeout = ? WHERE id = ?";

        jdbcTemplate.update(sql,
                tool.getLabel(),
                tool.getPrompt(),
                toJson(tool.getParams()),
                tool.getFunctionType().name(),
                tool.getDataSource(),
                tool.getSqlQuery(),
                tool.getHttpMethod(),
                tool.getHttpPath(),
                toJson(tool.getHttpHeaders()),
                tool.getHttpBody(),
                tool.getTimeout(),
                tool.getId()
        );
    }

    public Optional<ToolModel.Tool> findById(Long id) {
        String sql = "SELECT * FROM tool WHERE id = ?";
        return jdbcTemplate.query(sql, toolRowMapper, id).stream().findFirst();
    }

    public Optional<ToolModel.Tool> findByChatbotIdAndFuncName(Long chatbotId, String funcNameKey) {
        String sql = "SELECT * FROM tool WHERE chatbot_id = ? AND func_name_key = ?";
        return jdbcTemplate.query(sql, toolRowMapper, chatbotId, funcNameKey).stream().findFirst();
    }

    public List<ToolModel.Tool> findByChatbotId(Long chatbotId) {
        String sql = "SELECT * FROM tool WHERE chatbot_id = ?";
        return jdbcTemplate.query(sql, toolRowMapper, chatbotId);
    }

    public List<ToolModel.Tool> findAll() {
        String sql = "SELECT * FROM tool";
        return jdbcTemplate.query(sql, toolRowMapper);
    }

    public void delete(Long id) {
        String sql = "DELETE FROM tool WHERE id = ?";
        jdbcTemplate.update(sql, id);
    }

    public void deleteByChatbotId(Long chatbotId) {
        String sql = "DELETE FROM tool WHERE chatbot_id = ?";
        jdbcTemplate.update(sql, chatbotId);
    }

    private String toJson(Object obj) {
        if (obj == null) return null;
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to convert object to JSON", e);
        }
    }
}