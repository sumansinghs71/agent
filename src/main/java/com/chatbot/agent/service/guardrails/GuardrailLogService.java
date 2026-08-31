package com.chatbot.agent.service.guardrails;

import com.chatbot.agent.metrics.AgentMetrics;
import com.chatbot.agent.model.GuardrailModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class GuardrailLogService {

    private static final Logger log = LoggerFactory.getLogger(GuardrailLogService.class);

    private final AgentMetrics metrics;

    public GuardrailLogService(AgentMetrics metrics) {
        this.metrics = metrics;
    }

    public void logViolation(Long chatbotId,
                             String sessionId,
                             GuardrailModel.GuardrailType guardrailType,
                             GuardrailModel.GuardrailResult result,
                             String inputText) {

        // Single choke point for every guardrail block in the app.
        metrics.recordGuardrailViolation(
                guardrailType != null ? guardrailType.name() : null,
                result.getViolation() != null && result.getViolation().getType() != null
                        ? result.getViolation().getType().name() : null,
                result.getViolation() != null && result.getViolation().getSeverity() != null
                        ? result.getViolation().getSeverity().name() : null);

        log.warn("GUARDRAIL VIOLATION: chatbotId={}, type={}, violationType={}, severity={}, input={}",
                chatbotId,
                guardrailType,
                result.getViolation() != null ? result.getViolation().getType() : "UNKNOWN",
                result.getViolation() != null ? result.getViolation().getSeverity() : "UNKNOWN",
                inputText != null && inputText.length() > 100 ?
                        inputText.substring(0, 100) + "..." : inputText);

        // TODO: Save to database (guardrail_log table)
        // For now, just log to console
    }
}