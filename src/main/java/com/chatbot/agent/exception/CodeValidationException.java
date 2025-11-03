package com.chatbot.agent.exception;

import lombok.Getter;

/**
 * Thrown when code validation fails
 */
@Getter
public class CodeValidationException extends ToolExecutionException {

    private final String codeType; // "PYTHON" or "JAVASCRIPT"
    private final String validationError;

    public CodeValidationException(String message, String codeType, String validationError) {
        super(message, "CODE_VALIDATION_FAILED");
        this.codeType = codeType;
        this.validationError = validationError;
    }
}
