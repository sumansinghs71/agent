package com.chatbot.agent.service.tools;

import com.chatbot.agent.config.ToolExecutionProperties;
import com.chatbot.agent.exception.CodeValidationException;
import com.chatbot.agent.model.CodeValidationResult;
import com.chatbot.agent.model.ToolModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JavaScriptCodeWrapper - Validates and wraps JavaScript code
 * 
 * Ensures all JavaScript code follows the format:
 * function(data) {
 *     // user code
 *     return result;
 * }
 */
@Slf4j
@Component
public class JavaScriptCodeWrapper {
    
    private final ToolExecutionProperties config;
    
    // Pattern to match function(data) { ... }
    private static final Pattern FUNCTION_PATTERN = Pattern.compile(
            "^\\s*function\\s*\\(\\s*data\\s*\\)\\s*\\{",
            Pattern.MULTILINE | Pattern.DOTALL
    );
    
    public JavaScriptCodeWrapper(ToolExecutionProperties config) {
        this.config = config;
    }
    
    /**
     * Validate and wrap JavaScript code
     * 
     * @param tool The tool containing JavaScript code
     * @return ValidationResult with wrapped code
     */
    public CodeValidationResult validateAndWrap(ToolModel.Tool tool) {
        String code = tool.getJsCode();
        
        if (code == null || code.trim().isEmpty()) {
            return CodeValidationResult.invalid("JavaScript code is empty");
        }
        
        // Check size
        if (code.length() > config.getJavascript().getMaxCodeSizeBytes()) {
            return CodeValidationResult.invalid(
                    String.format("JavaScript code too large (%d bytes). Maximum: %d bytes",
                            code.length(), config.getJavascript().getMaxCodeSizeBytes())
            );
        }
        
        // Check if already in correct format
        Matcher matcher = FUNCTION_PATTERN.matcher(code);
        if (matcher.find()) {
            // Already wrapped - validate closing brace
            if (!hasMatchingClosingBrace(code)) {
                return CodeValidationResult.invalid("Missing closing brace for function");
            }
            
            log.debug("JavaScript code already in correct format for tool: {}", tool.getFuncNameKey());
            return CodeValidationResult.valid(code);
        }
        
        // Need to wrap the code
        String wrappedCode = wrapCode(code);
        
        log.debug("JavaScript code wrapped for tool: {}", tool.getFuncNameKey());
        return CodeValidationResult.valid(wrappedCode);
    }
    
    /**
     * Wrap user code in function(data) { } format
     */
    private String wrapCode(String userCode) {
        StringBuilder wrapped = new StringBuilder();
        
        wrapped.append("function(data) {\n");
        wrapped.append("    // User code\n");
        
        // Indent user code
        String[] lines = userCode.split("\n");
        for (String line : lines) {
            wrapped.append("    ").append(line).append("\n");
        }
        
        // Ensure there's a return statement if user didn't provide one
        if (!userCode.contains("return")) {
            wrapped.append("    \n");
            wrapped.append("    // Auto-generated return\n");
            wrapped.append("    return null;\n");
        }
        
        wrapped.append("}");
        
        return wrapped.toString();
    }
    
    /**
     * Check if code has matching closing brace
     */
    private boolean hasMatchingClosingBrace(String code) {
        int braceCount = 0;
        boolean inString = false;
        boolean inComment = false;
        char stringChar = 0;
        
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            char next = (i + 1 < code.length()) ? code.charAt(i + 1) : 0;
            
            // Handle comments
            if (!inString && c == '/' && next == '/') {
                inComment = true;
            }
            if (inComment && c == '\n') {
                inComment = false;
            }
            
            // Handle strings
            if (!inComment) {
                if ((c == '"' || c == '\'' || c == '`') && (i == 0 || code.charAt(i - 1) != '\\')) {
                    if (!inString) {
                        inString = true;
                        stringChar = c;
                    } else if (c == stringChar) {
                        inString = false;
                    }
                }
                
                // Count braces outside strings and comments
                if (!inString) {
                    if (c == '{') braceCount++;
                    if (c == '}') braceCount--;
                }
            }
        }
        
        return braceCount == 0;
    }
    
    /**
     * Validate code syntax (basic checks)
     */
    public void validateSyntax(String code) throws CodeValidationException {
        // Check for common syntax errors
        
        // Unmatched parentheses
        if (!isBalanced(code, '(', ')')) {
            throw new CodeValidationException(
                    "Unmatched parentheses in JavaScript code",
                    "JAVASCRIPT",
                    "Unmatched parentheses"
            );
        }
        
        // Unmatched brackets
        if (!isBalanced(code, '[', ']')) {
            throw new CodeValidationException(
                    "Unmatched brackets in JavaScript code",
                    "JAVASCRIPT",
                    "Unmatched brackets"
            );
        }
        
        // Unmatched braces
        if (!isBalanced(code, '{', '}')) {
            throw new CodeValidationException(
                    "Unmatched braces in JavaScript code",
                    "JAVASCRIPT",
                    "Unmatched braces"
            );
        }
    }
    
    /**
     * Check if brackets/braces/parens are balanced
     */
    private boolean isBalanced(String code, char open, char close) {
        int count = 0;
        boolean inString = false;
        char stringChar = 0;
        
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            
            // Track strings
            if ((c == '"' || c == '\'' || c == '`') && (i == 0 || code.charAt(i - 1) != '\\')) {
                if (!inString) {
                    inString = true;
                    stringChar = c;
                } else if (c == stringChar) {
                    inString = false;
                }
            }
            
            // Count outside strings
            if (!inString) {
                if (c == open) count++;
                if (c == close) count--;
                
                if (count < 0) return false; // More closing than opening
            }
        }
        
        return count == 0;
    }
}
