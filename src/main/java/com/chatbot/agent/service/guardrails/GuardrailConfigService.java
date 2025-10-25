package com.chatbot.agent.service.guardrails;

import com.chatbot.agent.model.GuardrailModel;
import org.springframework.stereotype.Service;

@Service
public class GuardrailConfigService {

    public GuardrailModel.GuardrailConfig getConfig(Long chatbotId) {
        // For now, return default config
        // Later, fetch from database per chatbot
        return getDefaultConfig();
    }

    private GuardrailModel.GuardrailConfig getDefaultConfig() {
        GuardrailModel.GuardrailConfig config = new GuardrailModel.GuardrailConfig();
        config.setEnableInputGuardrails(true);
        config.setEnableOutputGuardrails(true);
        config.setEnablePiiDetection(true);
        config.setEnableToxicityFilter(true);
        config.setEnableJailbreakDetection(true);
        config.setEnableHallucinationDetection(true);
        config.setToxicityThreshold(0.7);
        config.setHallucinationThreshold(0.6);
        config.setMaxTokens(4000);
        config.setMaxToolExecutionTime(30);
        return config;
    }
}