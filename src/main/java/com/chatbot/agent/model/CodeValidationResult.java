package com.chatbot.agent.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * CodeValidationResult - Result from code format validation
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CodeValidationResult {
    private boolean valid;
    private String error;
    private String wrappedCode; // Auto-wrapped code if needed
    private List<String> warnings;

    public static CodeValidationResult valid(String wrappedCode) {
        return CodeValidationResult.builder()
                .valid(true)
                .wrappedCode(wrappedCode)
                .build();
    }

    public static CodeValidationResult invalid(String error) {
        return CodeValidationResult.builder()
                .valid(false)
                .error(error)
                .build();
    }
}
