package com.chatbot.agent.config;

import com.chatbot.agent.service.tools.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * ToolExecutionConfig - Spring Bean Configuration
 *
 * CRITICAL: This configuration handles circular dependency between
 * ToolExecutionService and PythonJavaScriptToolExecutor
 */
@Configuration
public class ToolExecutionConfig {

    /**
     * PythonJavaScriptToolExecutor bean
     *
     * Note: ToolExecutionService is set via setter after bean creation
     * to avoid circular dependency
     */
    @Bean
    public PythonJavaScriptToolExecutor pythonJavaScriptToolExecutor(
            ToolExecutionProperties config,
            PythonScriptBuilder pythonScriptBuilder,
            JavaScriptCodeWrapper jsCodeWrapper,
            ObjectMapper jsonMapper) {

        return new PythonJavaScriptToolExecutor(
                pythonScriptBuilder,
                jsCodeWrapper,
                config
        );
    }

    /**
     * ToolExecutionService bean
     *
     * CRITICAL: Sets toolExecutionService in pyJsExecutor to complete
     * the circular dependency resolution
     */
    @Bean
    public ToolExecutionService toolExecutionService(
            ExecutionContextFactory contextFactory,
            ToolRegistryService toolRegistry,
            com.chatbot.agent.repository.ToolRepository toolRepository,
            PythonJavaScriptToolExecutor pyJsExecutor,
            com.chatbot.agent.service.guardrails.RuntimeGuardrailsService guardrailsService,
            com.chatbot.agent.service.guardrails.GuardrailLogService guardrailLogService,
            org.springframework.web.client.RestTemplate restTemplate,
            ObjectMapper objectMapper,
            ToolExecutionProperties config,
            DynamicDataSourceConfig.DynamicDataSourceManager dataSourceManager) {

        ToolExecutionService service = new ToolExecutionService(
                contextFactory,
                toolRegistry,
                toolRepository,
                pyJsExecutor,
                guardrailsService,
                guardrailLogService,
                restTemplate,
                objectMapper,
                config,
                dataSourceManager
        );

        // CRITICAL: Complete circular dependency
        pyJsExecutor.setToolExecutionService(service);

        return service;
    }
}