package com.chatbot.agent.service.tools;

import com.chatbot.agent.config.ToolExecutionProperties;
import com.chatbot.agent.exception.DangerousCodeException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.regex.Pattern;

/**
 * CodeValidatorService - Validates Python and JavaScript code for dangerous patterns
 *
 * Security validation to block:
 * - File system access
 * - Network access
 * - Process execution
 * - Code injection
 * - Import of dangerous modules
 */
@Service
@Slf4j
public class CodeValidatorService {

    private final ToolExecutionProperties config;

    // Dangerous Python patterns
    private static final List<String> PYTHON_DANGEROUS = List.of(
            "import os",
            "import subprocess",
            "import sys",
            "__import__",
            "exec\\(",
            "eval\\(",
            "open\\(",
            "file\\(",
            "input\\(",
            "raw_input\\(",
            "compile\\(",
            "reload\\(",
            "execfile\\(",
            "import socket",
            "import urllib",
            "import requests",
            "import http",
            "import ftplib",
            "import smtplib",
            "from os import",
            "from subprocess import"
    );

    // Dangerous JavaScript patterns
    private static final List<String> JS_DANGEROUS = List.of(
            "eval\\(",
            "Function\\(",
            "setTimeout",
            "setInterval",
            "require\\(",
            "import\\(",
            "XMLHttpRequest",
            "fetch\\(",
            "process\\.exit",
            "child_process",
            "__dirname",
            "__filename",
            "global\\.",
            "globalThis\\."
    );

    public CodeValidatorService(ToolExecutionProperties config) {
        this.config = config;
    }

    /**
     * Validate Python code for dangerous patterns
     *
     * @param code Python code to validate
     * @throws DangerousCodeException if dangerous pattern found
     */
    public void validatePythonCode(String code) throws DangerousCodeException {

        if (!config.getSecurity().isBlockDangerousPatterns()) {
            log.debug("Dangerous pattern blocking is disabled, skipping validation");
            return;
        }

        if (code == null || code.trim().isEmpty()) {
            throw new DangerousCodeException(
                    "Python code is empty",
                    "EMPTY_CODE",
                    "PYTHON"
            );
        }

        // Check size
        if (code.length() > config.getPython().getMaxCodeSizeBytes()) {
            throw new DangerousCodeException(
                    String.format("Python code too large (%d bytes). Maximum: %d bytes",
                            code.length(), config.getPython().getMaxCodeSizeBytes()),
                    "CODE_TOO_LARGE",
                    "PYTHON"
            );
        }

        // Check for dangerous patterns
        for (String pattern : PYTHON_DANGEROUS) {
            if (Pattern.compile(pattern, Pattern.CASE_INSENSITIVE).matcher(code).find()) {
                log.warn("Dangerous Python pattern detected: {}", pattern);
                throw new DangerousCodeException(
                        "Dangerous Python operation detected: " + pattern.replace("\\", ""),
                        pattern,
                        "PYTHON"
                );
            }
        }

        log.debug("Python code validation passed");
    }

    /**
     * Validate JavaScript code for dangerous patterns
     *
     * @param code JavaScript code to validate
     * @throws DangerousCodeException if dangerous pattern found
     */
    public void validateJavaScriptCode(String code) throws DangerousCodeException {

        if (!config.getSecurity().isBlockDangerousPatterns()) {
            log.debug("Dangerous pattern blocking is disabled, skipping validation");
            return;
        }

        if (code == null || code.trim().isEmpty()) {
            throw new DangerousCodeException(
                    "JavaScript code is empty",
                    "EMPTY_CODE",
                    "JAVASCRIPT"
            );
        }

        // Check size
        if (code.length() > config.getJavascript().getMaxCodeSizeBytes()) {
            throw new DangerousCodeException(
                    String.format("JavaScript code too large (%d bytes). Maximum: %d bytes",
                            code.length(), config.getJavascript().getMaxCodeSizeBytes()),
                    "CODE_TOO_LARGE",
                    "JAVASCRIPT"
            );
        }

        // Check for dangerous patterns
        for (String pattern : JS_DANGEROUS) {
            if (Pattern.compile(pattern, Pattern.CASE_INSENSITIVE).matcher(code).find()) {
                log.warn("Dangerous JavaScript pattern detected: {}", pattern);
                throw new DangerousCodeException(
                        "Dangerous JavaScript operation detected: " + pattern.replace("\\", ""),
                        pattern,
                        "JAVASCRIPT"
                );
            }
        }

        log.debug("JavaScript code validation passed");
    }

    /**
     * Validate code based on type
     *
     * @param code Code to validate
     * @param type "PYTHON" or "JAVASCRIPT"
     * @throws DangerousCodeException if validation fails
     */
    public void validateCode(String code, String type) throws DangerousCodeException {
        if ("PYTHON".equalsIgnoreCase(type)) {
            validatePythonCode(code);
        } else if ("JAVASCRIPT".equalsIgnoreCase(type)) {
            validateJavaScriptCode(code);
        } else {
            log.warn("Unknown code type for validation: {}", type);
        }
    }
}