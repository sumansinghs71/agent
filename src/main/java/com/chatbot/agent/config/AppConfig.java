package com.chatbot.agent.config;

import org.apache.tika.Tika;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.client.RestTemplate;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Configuration
public class AppConfig {

    @Value("${spring.datasource.url}")
    private String mysqlUrl;

    @Value("${spring.datasource.username}")
    private String mysqlUsername;

    @Value("${spring.datasource.password}")
    private String mysqlPassword;

    @Value("${llama.vector-db.url}")
    private String pgVectorUrl;

    @Value("${llama.vector-db.username}")
    private String pgVectorUsername;

    @Value("${llama.vector-db.password}")
    private String pgVectorPassword;

    // Primary MySQL DataSource
    @Bean(name = "mysqlDataSource")
    public DataSource mysqlDataSource() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
        dataSource.setUrl(mysqlUrl);
        dataSource.setUsername(mysqlUsername);
        dataSource.setPassword(mysqlPassword);
        return dataSource;
    }

    // PostgreSQL Vector DB DataSource
    @Bean(name = "pgVectorDataSource")
    public DataSource pgVectorDataSource() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl(pgVectorUrl);
        dataSource.setUsername(pgVectorUsername);
        dataSource.setPassword(pgVectorPassword);
        return dataSource;
    }

    // JdbcTemplate for MySQL
    @Bean(name = "mysqlJdbcTemplate")
    @Primary
    public JdbcTemplate mysqlJdbcTemplate() {
        return new JdbcTemplate(mysqlDataSource());
    }

    // JdbcTemplate for Vector DB (Postgres)
    @Bean(name = "pgVectorJdbcTemplate")
    public JdbcTemplate pgVectorJdbcTemplate() {
        return new JdbcTemplate(pgVectorDataSource());
    }

    // RestTemplate with proper JSON handling
    @Bean
    public RestTemplate restTemplate() {
        // Configure timeout settings
        HttpComponentsClientHttpRequestFactory factory = new HttpComponentsClientHttpRequestFactory();
        factory.setConnectTimeout(5000);  // 5 seconds
        factory.setConnectionRequestTimeout(5000); // 5 seconds
        //factory.setReadTimeout(30000);    // 30 seconds

        // Create RestTemplate with timeout settings
        RestTemplate restTemplate = new RestTemplate(factory);

        // Create custom message converters
        List<HttpMessageConverter<?>> converters = new ArrayList<>();

        // 1. JSON converter with proper configuration
        MappingJackson2HttpMessageConverter jsonConverter = new MappingJackson2HttpMessageConverter();
        converters.add(jsonConverter);

        // 2. String converter with UTF-8 encoding
        StringHttpMessageConverter stringConverter = new StringHttpMessageConverter(StandardCharsets.UTF_8);
        converters.add(stringConverter);

        // Set the converters
        restTemplate.setMessageConverters(converters);

        return restTemplate;
    }

    // Apache Tika for document parsing
    @Bean
    public Tika tika() {
        return new Tika();
    }
}