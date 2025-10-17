package com.chatbot.agent.service.guardrails;

import com.chatbot.agent.model.GuardrailModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class GuardrailLogService {

    private static final Logger log = LoggerFactory.getLogger(GuardrailLogService.class);

    public void logViolation(Long chatbotId,
                             String sessionId,
                             GuardrailModel.GuardrailType guardrailType,
                             GuardrailModel.GuardrailResult result,
                             String inputText) {

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