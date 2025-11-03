package com.chatbot.agent.exception;

import lombok.Getter;

/**
 * Thrown when dangerous code patterns detected
 */
@Getter
public class DangerousCodeException extends ToolExecutionException {

    private final String detectedPattern;
    private final String codeType;

    public DangerousCodeException(String message, String pattern, String codeType) {
        super(message, "DANGEROUS_CODE_DETECTED");
        this.detectedPattern = pattern;
        this.codeType = codeType;
    }
}
