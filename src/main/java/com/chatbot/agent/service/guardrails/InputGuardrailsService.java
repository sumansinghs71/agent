package com.chatbot.agent.service.guardrails;

import com.chatbot.agent.model.GuardrailModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class InputGuardrailsService {

    private static final Logger log = LoggerFactory.getLogger(InputGuardrailsService.class);

    // PII Patterns
    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Z|a-z]{2,}\\b");
    private static final Pattern SSN_PATTERN =
            Pattern.compile("\\b\\d{3}-\\d{2}-\\d{4}\\b");
    private static final Pattern CREDIT_CARD_PATTERN =
            Pattern.compile("\\b\\d{4}[\\s-]?\\d{4}[\\s-]?\\d{4}[\\s-]?\\d{4}\\b");
    private static final Pattern PHONE_PATTERN =
            Pattern.compile("\\b\\d{3}[-.]?\\d{3}[-.]?\\d{4}\\b");

    // Jailbreak patterns
    private static final List<String> JAILBREAK_PATTERNS = Arrays.asList(
            "ignore previous instructions",
            "ignore all previous",
            "disregard previous",
            "forget previous instructions",
            "you are now",
            "new role:",
            "act as if",
            "pretend you are",
            "simulate being",
            "from now on",
            "developer mode",
            "god mode",
            "jailbreak",
            "dan mode"
    );

    // Prompt injection patterns
    private static final List<String> PROMPT_INJECTION_PATTERNS = Arrays.asList(
            "system:",
            "assistant:",
            "user:",
            "[INST]",
            "</s>",
            "<|im_start|>",
            "<|im_end|>",
            "###instruction",
            "###response"
    );

    // Toxic keywords (basic list - use API for comprehensive check)
    private static final List<String> TOXIC_KEYWORDS = Arrays.asList(
            // Add your list of inappropriate words
            // This is just a placeholder
    );

    /**
     * Main guardrail validation method
     */
    public GuardrailModel.GuardrailResult validateInput(String input,
                                                        GuardrailModel.GuardrailConfig config) {
        log.debug("Validating input with guardrails");

        GuardrailModel.GuardrailResult result = new GuardrailModel.GuardrailResult();
        result.setAllowed(true);
        result.setSanitizedInput(input);
        result.setRiskScore(0.0);

        // 1. Check input length
        if (input.length() > config.getMaxTokens() * 4) { // Rough token estimate
            result.setAllowed(false);
            result.setViolation(createViolation(
                    GuardrailModel.ViolationType.EXCESSIVE_LENGTH,
                    "Input exceeds maximum allowed length",
                    GuardrailModel.SeverityLevel.LOW
            ));
            return result;
        }

        // 2. Detect PII
        if (config.isEnablePiiDetection()) {
            GuardrailModel.GuardrailResult piiResult = detectPii(input);
            if (!piiResult.isAllowed()) {
                result.setAllowed(false);
                result.setViolation(piiResult.getViolation());
                result.setSanitizedInput(piiResult.getSanitizedInput());
                result.setRiskScore(Math.max(result.getRiskScore(), 0.7));
            } else {
                result.setSanitizedInput(piiResult.getSanitizedInput());
            }
        }

        // 3. Detect jailbreak attempts
        if (config.isEnableJailbreakDetection()) {
            GuardrailModel.GuardrailResult jailbreakResult = detectJailbreak(input);
            if (!jailbreakResult.isAllowed()) {
                result.setAllowed(false);
                result.setViolation(jailbreakResult.getViolation());
                result.setRiskScore(Math.max(result.getRiskScore(), 0.9));
                return result;
            }
        }

        // 4. Detect prompt injection
        GuardrailModel.GuardrailResult injectionResult = detectPromptInjection(input);
        if (!injectionResult.isAllowed()) {
            result.setAllowed(false);
            result.setViolation(injectionResult.getViolation());
            result.setRiskScore(Math.max(result.getRiskScore(), 0.85));
            return result;
        }

        // 5. Check for toxic content
        if (config.isEnableToxicityFilter()) {
            GuardrailModel.GuardrailResult toxicityResult = detectToxicity(input, config);
            if (!toxicityResult.isAllowed()) {
                result.setAllowed(false);
                result.setViolation(toxicityResult.getViolation());
                result.setRiskScore(Math.max(result.getRiskScore(), 0.8));
                return result;
            }
        }

        log.debug("Input validation passed. Risk score: {}", result.getRiskScore());
        return result;
    }

    /**
     * Detect and mask PII
     */
    private GuardrailModel.GuardrailResult detectPii(String input) {
        GuardrailModel.GuardrailResult result = new GuardrailModel.GuardrailResult();
        result.setAllowed(true);

        String sanitized = input;
        boolean piiFound = false;
        StringBuilder detectedPii = new StringBuilder();

        // Detect and mask email
        Matcher emailMatcher = EMAIL_PATTERN.matcher(sanitized);
        if (emailMatcher.find()) {
            piiFound = true;
            detectedPii.append("email, ");
            sanitized = emailMatcher.replaceAll("[EMAIL_REDACTED]");
        }

        // Detect and mask SSN
        Matcher ssnMatcher = SSN_PATTERN.matcher(sanitized);
        if (ssnMatcher.find()) {
            piiFound = true;
            detectedPii.append("SSN, ");
            sanitized = ssnMatcher.replaceAll("[SSN_REDACTED]");
        }

        // Detect and mask credit card
        Matcher cardMatcher = CREDIT_CARD_PATTERN.matcher(sanitized);
        if (cardMatcher.find()) {
            piiFound = true;
            detectedPii.append("credit card, ");
            sanitized = cardMatcher.replaceAll("[CARD_REDACTED]");
        }

        // Detect and mask phone
        Matcher phoneMatcher = PHONE_PATTERN.matcher(sanitized);
        if (phoneMatcher.find()) {
            piiFound = true;
            detectedPii.append("phone, ");
            sanitized = phoneMatcher.replaceAll("[PHONE_REDACTED]");
        }

        result.setSanitizedInput(sanitized);

        if (piiFound) {
            log.warn("PII detected and masked: {}", detectedPii);
            result.setViolation(createViolation(
                    GuardrailModel.ViolationType.PII_DETECTED,
                    "PII detected and sanitized: " + detectedPii,
                    GuardrailModel.SeverityLevel.MEDIUM
            ));
            // Still allow but with sanitized input
            result.setAllowed(true);
        }

        return result;
    }

    /**
     * Detect jailbreak attempts
     */
    private GuardrailModel.GuardrailResult detectJailbreak(String input) {
        GuardrailModel.GuardrailResult result = new GuardrailModel.GuardrailResult();
        result.setAllowed(true);

        String lowerInput = input.toLowerCase();

        for (String pattern : JAILBREAK_PATTERNS) {
            if (lowerInput.contains(pattern)) {
                log.warn("Jailbreak attempt detected: {}", pattern);
                result.setAllowed(false);
                result.setViolation(createViolation(
                        GuardrailModel.ViolationType.JAILBREAK_ATTEMPT,
                        "Potential jailbreak attempt detected: " + pattern,
                        GuardrailModel.SeverityLevel.CRITICAL
                ));
                return result;
            }
        }

        return result;
    }

    /**
     * Detect prompt injection
     */
    private GuardrailModel.GuardrailResult detectPromptInjection(String input) {
        GuardrailModel.GuardrailResult result = new GuardrailModel.GuardrailResult();
        result.setAllowed(true);

        for (String pattern : PROMPT_INJECTION_PATTERNS) {
            if (input.contains(pattern)) {
                log.warn("Prompt injection detected: {}", pattern);
                result.setAllowed(false);
                result.setViolation(createViolation(
                        GuardrailModel.ViolationType.PROMPT_INJECTION,
                        "Potential prompt injection detected: " + pattern,
                        GuardrailModel.SeverityLevel.HIGH
                ));
                return result;
            }
        }

        return result;
    }

    /**
     * Detect toxic content (basic keyword-based)
     * In production, use Perspective API or Azure Content Safety
     */
    private GuardrailModel.GuardrailResult detectToxicity(String input,
                                                          GuardrailModel.GuardrailConfig config) {
        GuardrailModel.GuardrailResult result = new GuardrailModel.GuardrailResult();
        result.setAllowed(true);

        String lowerInput = input.toLowerCase();

        // Basic keyword check (replace with API call in production)
        for (String keyword : TOXIC_KEYWORDS) {
            if (lowerInput.contains(keyword)) {
                log.warn("Toxic content detected");
                result.setAllowed(false);
                result.setViolation(createViolation(
                        GuardrailModel.ViolationType.TOXIC_CONTENT,
                        "Toxic or inappropriate content detected",
                        GuardrailModel.SeverityLevel.HIGH
                ));
                return result;
            }
        }

        return result;
    }

    /**
     * Helper to create violation object
     */
    private GuardrailModel.GuardrailViolation createViolation(
            GuardrailModel.ViolationType type,
            String description,
            GuardrailModel.SeverityLevel severity) {

        GuardrailModel.GuardrailViolation violation = new GuardrailModel.GuardrailViolation();
        violation.setType(type);
        violation.setDescription(description);
        violation.setSeverity(severity);
        violation.setRecommendation(getRecommendation(type));
        return violation;
    }

    /**
     * Get recommendation based on violation type
     */
    private String getRecommendation(GuardrailModel.ViolationType type) {
        switch (type) {
            case PII_DETECTED:
                return "PII has been automatically redacted. Please avoid sharing sensitive information.";
            case JAILBREAK_ATTEMPT:
                return "This request appears to attempt to bypass safety measures. Please rephrase your query.";
            case PROMPT_INJECTION:
                return "Your input contains patterns that could interfere with system operation. Please rephrase.";
            case TOXIC_CONTENT:
                return "Please keep your messages respectful and appropriate.";
            case EXCESSIVE_LENGTH:
                return "Please shorten your message. Maximum length exceeded.";
            default:
                return "Please modify your request to comply with usage policies.";
        }
    }
}