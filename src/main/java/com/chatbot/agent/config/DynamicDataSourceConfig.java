package com.chatbot.agent.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Configuration
public class DynamicDataSourceConfig {

    @Value("classpath:static/datasource.properties")
    private Resource datasourcesResource;

    private final Map<String, DataSource> dataSourceCache = new ConcurrentHashMap<>();
    private Properties datasourceProperties;

    @Bean
    public DynamicDataSourceManager dynamicDataSourceManager() {
        try {
            loadDatasourceProperties();
        } catch (IOException e) {
            // Fail fast with an unchecked exception that gives a clear startup error
            throw new IllegalStateException(
                    "Failed to load datasource properties from classpath:static/datasource.properties. " +
                            "Please ensure the file exists and is readable.",
                    e
            );
        }
        return new DynamicDataSourceManager(datasourceProperties, dataSourceCache);
    }

    private void loadDatasourceProperties() throws IOException {
        datasourceProperties = new Properties();
        try (InputStream input = datasourcesResource.getInputStream()) {
            datasourceProperties.load(input);
        }
    }

    public static class DynamicDataSourceManager {
        private final Properties properties;
        private final Map<String, DataSource> cache;
        private final Set<String> availableDataSources;

        public DynamicDataSourceManager(Properties properties, Map<String, DataSource> cache) {
            this.properties = properties;
            this.cache = cache;
            String datasourcesList = properties.getProperty("datasources", "");
            // Split by comma and trim whitespace from each datasource name
            this.availableDataSources = new HashSet<>();
            if (!datasourcesList.isEmpty()) {
                for (String ds : datasourcesList.split(",")) {
                    String trimmed = ds.trim();
                    if (!trimmed.isEmpty()) {
                        this.availableDataSources.add(trimmed);
                    }
                }
            }
        }

        public DataSource getDataSource(String name) {
            String trimmedName = name.trim();
            if (!availableDataSources.contains(trimmedName)) {
                throw new IllegalArgumentException(
                        "DataSource not configured: " + trimmedName +
                                ". Available datasources: " + availableDataSources
                );
            }

            return cache.computeIfAbsent(trimmedName, this::createDataSource);
        }

        private DataSource createDataSource(String name) {
            // Get properties with the app name prefix
            String driverClassName = getProperty(name, "driverClassName");
            String url = getProperty(name, "url");
            String username = getProperty(name, "username");
            String password = getProperty(name, "password");
            String initialPoolSize = getProperty(name, "initialPoolSize", "1");
            String maxPoolSize = getProperty(name, "maxPoolSize", "5");
            String minPoolSize = getProperty(name, "minPoolSize", "1");
            String validationQuery = getProperty(name, "validationQuery", null);
            String isolationLevel = getProperty(name, "isolationLevel", "READ_COMMITTED");

            // Validate required properties
            if (driverClassName == null || driverClassName.isEmpty()) {
                throw new IllegalArgumentException(
                        "Missing required property: " + name + ".driverClassName"
                );
            }
            if (url == null || url.isEmpty()) {
                throw new IllegalArgumentException(
                        "Missing required property: " + name + ".url"
                );
            }
            if (username == null || username.isEmpty()) {
                throw new IllegalArgumentException(
                        "Missing required property: " + name + ".username"
                );
            }
            if (password == null || password.isEmpty()) {
                throw new IllegalArgumentException(
                        "Missing required property: " + name + ".password"
                );
            }

            HikariConfig config = new HikariConfig();
            config.setDriverClassName(driverClassName);
            config.setJdbcUrl(url);
            config.setUsername(username);
            config.setPassword(password);
            config.setMinimumIdle(Integer.parseInt(minPoolSize));
            config.setMaximumPoolSize(Integer.parseInt(maxPoolSize));

            if (validationQuery != null && !validationQuery.isEmpty()) {
                config.setConnectionTestQuery(validationQuery);
            }

            config.setTransactionIsolation("TRANSACTION_" + isolationLevel);
            config.setPoolName(name + "-pool");
            config.setConnectionTimeout(30000);
            config.setIdleTimeout(600000);
            config.setMaxLifetime(1800000);

            return new HikariDataSource(config);
        }

        /**
         * Helper method to get property with app name prefix
         */
        private String getProperty(String appName, String propertyName) {
            return properties.getProperty(appName + "." + propertyName);
        }

        /**
         * Helper method to get property with default value
         */
        private String getProperty(String appName, String propertyName, String defaultValue) {
            return properties.getProperty(appName + "." + propertyName, defaultValue);
        }

        public Connection getConnection(String dataSourceName) throws Exception {
            return getDataSource(dataSourceName).getConnection();
        }

        public Set<String> getAvailableDataSources() {
            return new HashSet<>(availableDataSources);
        }
    }
}