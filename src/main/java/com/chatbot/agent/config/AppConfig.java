package com.chatbot.agent.config;

import org.apache.tika.Tika;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.core5.http.io.SocketConfig;
import org.apache.hc.core5.util.Timeout;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.client.RestTemplate;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
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

    /** Time to establish a TCP connection. */
    private static final int REST_CONNECT_TIMEOUT_MS = 5_000;

    /**
     * Time to wait for response bytes. Bounded above by the per-tool timeout, which the execution
     * context clamps again to the chain's remaining aggregate budget.
     */
    private static final int REST_READ_TIMEOUT_MS = 20_000;

    /** Time to wait for a connection from the pool - a saturated pool must fail, not queue forever. */
    private static final int REST_CONNECTION_REQUEST_TIMEOUT_MS = 3_000;

    // RestTemplate with proper JSON handling
    @Bean
    public RestTemplate restTemplate() {
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(Timeout.ofMilliseconds(REST_CONNECT_TIMEOUT_MS))
                .setResponseTimeout(Timeout.ofMilliseconds(REST_READ_TIMEOUT_MS))
                .setConnectionRequestTimeout(Timeout.ofMilliseconds(REST_CONNECTION_REQUEST_TIMEOUT_MS))
                .build();

        PoolingHttpClientConnectionManager connectionManager = new PoolingHttpClientConnectionManager();
        connectionManager.setDefaultSocketConfig(SocketConfig.custom()
                .setSoTimeout(Timeout.ofMilliseconds(REST_READ_TIMEOUT_MS))
                .build());
        connectionManager.setMaxTotal(50);
        connectionManager.setDefaultMaxPerRoute(10);

        CloseableHttpClient httpClient = HttpClients.custom()
                .setDefaultRequestConfig(requestConfig)
                .setConnectionManager(connectionManager)
                .build();

        HttpComponentsClientHttpRequestFactory httpFactory =
                new HttpComponentsClientHttpRequestFactory(httpClient);

        // Both timeouts are mandatory. The read timeout was previously commented out, which meant a
        // downstream that accepted the connection and then never responded held the calling request
        // thread forever - for REST tools AND for every Azure OpenAI call, since they share this
        // RestTemplate. A slow dependency could exhaust the servlet thread pool with no error and
        // no metric.
        //
        // Note on the API: Spring Framework 6.1 removed
        // HttpComponentsClientHttpRequestFactory#setReadTimeout, which is the likely reason the
        // original line was commented out rather than fixed. With HttpClient 5 the response
        // timeout belongs on RequestConfig and the socket timeout on the connection manager, so
        // the client is built explicitly here.

        // ✅ Wrap with BufferingClientHttpRequestFactory to read entire stream safely
        RestTemplate restTemplate = new RestTemplate(
                new BufferingClientHttpRequestFactory(httpFactory)
        );

        // ✅ Use default converters (don’t replace!)
        // only extend JSON charset variants
        restTemplate.getMessageConverters().stream()
                .filter(c -> c instanceof MappingJackson2HttpMessageConverter)
                .map(c -> (MappingJackson2HttpMessageConverter) c)
                .forEach(c -> {
                    List<MediaType> types = new ArrayList<>(c.getSupportedMediaTypes());
                    types.add(MediaType.valueOf("application/json;charset=UTF-8"));
                    types.add(MediaType.valueOf("application/json;charset=utf-8"));
                    c.setSupportedMediaTypes(types);
                });

        return restTemplate;
    }





    // Apache Tika for document parsing
    @Bean
    public Tika tika() {
        return new Tika();
    }
}