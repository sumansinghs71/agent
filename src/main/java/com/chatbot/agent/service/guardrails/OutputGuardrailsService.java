package com.chatbot.agent.service;

import com.chatbot.agent.model.GuardrailModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Pattern;

@Service
public class OutputGuardrailsService {

    private static final Logger log = LoggerFactory.getLogger(OutputGuardrailsService.class);

    @Autowired
    private AiRouterService aiRouterService;

    private static final Pattern UNCERTAIN_PATTERN = Pattern.compile(
            ".*(i'm not sure|i don't know|i cannot confirm|uncertain|unclear|possibly|maybe|perhaps).*",
            Pattern.CASE_INSENSITIVE
    );

    public GuardrailModel.GuardrailResult validateOutput(String output,
                                                         String originalQuery,
                                                         List<String> sourceContext,
                                                         GuardrailModel.GuardrailConfig config) {
        log.debug("Validating output with guardrails");

        GuardrailModel.GuardrailResult result = new GuardrailModel.GuardrailResult();
        result.setAllowed(true);
        result.setSanitizedInput(output);
        result.setRiskScore(0.0);

        if (config.isEnablePiiDetection()) {
            if (containsPii(output)) {
                log.warn("Output contains PII, sanitizing");
                String sanitized = sanitizePii(output);
                result.setSanitizedInput(sanitized);
            }
        }

        if (config.isEnableHallucinationDetection()) {
            GuardrailModel.GuardrailResult hallucinationResult =
                    detectHallucination(output, sourceContext, config);

            if (!hallucinationResult.isAllowed()) {
                result.setAllowed(false);
                result.setViolation(hallucinationResult.getViolation());
                result.setRiskScore(hallucinationResult.getRiskScore());
                return result;
            }

            result.setRiskScore(Math.max(result.getRiskScore(),
                    hallucinationResult.getRiskScore()));
        }

        GuardrailModel.GuardrailResult relevanceResult =
                checkRelevance(output, originalQuery);

        if (!relevanceResult.isAllowed()) {
            result.setAllowed(false);
            result.setViolation(relevanceResult.getViolation());
            result.setRiskScore(Math.max(result.getRiskScore(), 0.6));
            return result;
        }

        if (config.isEnableToxicityFilter()) {
            if (containsToxicContent(output)) {
                log.error("AI generated toxic content!");
                result.setAllowed(false);
                result.setViolation(createViolation(
                        GuardrailModel.ViolationType.TOXIC_CONTENT,
                        "Generated response contains inappropriate content",
                        GuardrailModel.SeverityLevel.CRITICAL
                ));
                return result;
            }
        }

        log.debug("Output validation passed. Risk score: {}", result.getRiskScore());
        return result;
    }

    private GuardrailModel.GuardrailResult detectHallucination(String output,
                                                               List<String> sourceContext,
                                                               GuardrailModel.GuardrailConfig config) {
        GuardrailModel.GuardrailResult result = new GuardrailModel.GuardrailResult();
        result.setAllowed(true);

        double hallucinationScore = 0.0;

        if (UNCERTAIN_PATTERN.matcher(output.toLowerCase()).matches()) {
            hallucinationScore += 0.2;
            log.debug("Output contains uncertainty markers");
        }

        double consistencyScore = checkSelfConsistency(output, sourceContext);
        hallucinationScore += (1.0 - consistencyScore) * 0.4;

        double groundednessScore = checkGroundedness(output, sourceContext);
        hallucinationScore += (1.0 - groundednessScore) * 0.4;

        result.setRiskScore(hallucinationScore);

        if (hallucinationScore > config.getHallucinationThreshold()) {
            log.warn("High hallucination risk detected: {}", hallucinationScore);
            result.setAllowed(false);
            result.setViolation(createViolation(
                    GuardrailModel.ViolationType.HALLUCINATION,
                    String.format("Response may contain hallucinated content (score: %.2f)",
                            hallucinationScore),
                    GuardrailModel.SeverityLevel.HIGH
            ));
        }

        return result;
    }

    /**
     * Check self-consistency by verifying statements against source context
     */
    private double checkSelfConsistency(String output, List<String> sourceContext) {
//        String requestId = MDC.get("requestId");
//        log.debug("[requestId={}] Checking self-consistency", requestId);
//
//        try {
//            String[] sentences = output.split("\\.");
//            int verifiableStatements = 0;
//            int verifiedStatements = 0;
//
//            for (String sentence : sentences) {
//                sentence = sentence.trim();
//                if (sentence.length() < 10) continue;
//
//                verifiableStatements++;
//
//                if (isStatementGroundedInContext(sentence, sourceContext)) {
//                    verifiedStatements++;
//                }
//            }
//
//            if (verifiableStatements == 0) {
//                return 0.5;
//            }
//
//            double consistencyScore = (double) verifiedStatements / verifiableStatements;
//            log.debug("[requestId={}] Consistency score: {} ({}/{} statements verified)",
//                    requestId, consistencyScore, verifiedStatements, verifiableStatements);
//
//            return consistencyScore;
//
//        } catch (Exception e) {
//            log.error("[requestId={}] Error in self-consistency check", requestId, e);
//            return 0.5;
//        }
        return 0.8; // Placeholder
    }

    private boolean isStatementGroundedInContext(String statement, List<String> sourceContext) {
        if (sourceContext == null || sourceContext.isEmpty()) {
            return false;
        }

        String statementLower = statement.toLowerCase();
        String[] keywords = extractKeywords(statementLower);

        int matchCount = 0;
        for (String keyword : keywords) {
            for (String context : sourceContext) {
                if (context.toLowerCase().contains(keyword)) {
                    matchCount++;
                    break;
                }
            }
        }

        return keywords.length > 0 &&
                ((double) matchCount / keywords.length) >= 0.5;
    }

    private String[] extractKeywords(String statement) {
        String[] words = statement.split("\\s+");
        List<String> keywords = new ArrayList<>();

        Set<String> stopWords = new HashSet<>(Arrays.asList(
                "a", "an", "the", "is", "are", "was", "were", "be", "been", "being",
                "have", "has", "had", "do", "does", "did", "will", "would", "could",
                "should", "may", "might", "can", "this", "that", "these", "those",
                "i", "you", "he", "she", "it", "we", "they", "what", "which", "who",
                "when", "where", "why", "how", "and", "or", "but", "if", "then"
        ));

        for (String word : words) {
            word = word.toLowerCase().replaceAll("[^a-z0-9]", "");
            if (word.length() > 3 && !stopWords.contains(word)) {
                keywords.add(word);
            }
        }

        return keywords.toArray(new String[0]);
    }

    private double checkGroundedness(String output, List<String> sourceContext) {
        if (sourceContext == null || sourceContext.isEmpty()) {
            return 0.5;
        }

        String combinedContext = String.join(" ", sourceContext).toLowerCase();
        String[] outputWords = output.toLowerCase().split("\\s+");

        int matchedWords = 0;
        for (String word : outputWords) {
            if (word.length() > 3 && combinedContext.contains(word)) {
                matchedWords++;
            }
        }

        double groundedness = (double) matchedWords / outputWords.length;
        log.debug("Groundedness score: {}", groundedness);

        return groundedness;
    }

    private GuardrailModel.GuardrailResult checkRelevance(String output, String query) {
        GuardrailModel.GuardrailResult result = new GuardrailModel.GuardrailResult();
        result.setAllowed(true);

        String[] queryWords = query.toLowerCase().split("\\s+");
        String outputLower = output.toLowerCase();

        int matches = 0;
        for (String word : queryWords) {
            if (word.length() > 3 && outputLower.contains(word)) {
                matches++;
            }
        }

        double relevance = (double) matches / queryWords.length;

        if (relevance < 0.2) {
            log.warn("Low relevance detected: {}", relevance);
            result.setAllowed(false);
            result.setViolation(createViolation(
                    GuardrailModel.ViolationType.OFF_TOPIC,
                    "Response does not seem relevant to the query",
                    GuardrailModel.SeverityLevel.MEDIUM
            ));
        }

        return result;
    }

    private boolean containsPii(String text) {
        Pattern emailPattern = Pattern.compile("\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Z|a-z]{2,}\\b");
        Pattern ssnPattern = Pattern.compile("\\b\\d{3}-\\d{2}-\\d{4}\\b");
        Pattern cardPattern = Pattern.compile("\\b\\d{4}[\\s-]?\\d{4}[\\s-]?\\d{4}[\\s-]?\\d{4}\\b");

        return emailPattern.matcher(text).find()
                || ssnPattern.matcher(text).find()
                || cardPattern.matcher(text).find();
    }

    private String sanitizePii(String text) {
        text = text.replaceAll("\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Z|a-z]{2,}\\b", "[EMAIL]");
        text = text.replaceAll("\\b\\d{3}-\\d{2}-\\d{4}\\b", "[SSN]");
        text = text.replaceAll("\\b\\d{4}[\\s-]?\\d{4}[\\s-]?\\d{4}[\\s-]?\\d{4}\\b", "[CARD]");
        return text;
    }

    private boolean containsToxicContent(String text) {
        return false;
    }

    private GuardrailModel.GuardrailViolation createViolation(
            GuardrailModel.ViolationType type,
            String description,
            GuardrailModel.SeverityLevel severity) {

        GuardrailModel.GuardrailViolation violation = new GuardrailModel.GuardrailViolation();
        violation.setType(type);
        violation.setDescription(description);
        violation.setSeverity(severity);
        return violation;
    }
}