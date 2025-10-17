package com.chatbot.agent.service.guardrails;

import com.chatbot.agent.model.GuardrailModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.Arrays;
import java.util.List;

@Service
public class RuntimeGuardrailsService {

    private static final Logger log = LoggerFactory.getLogger(RuntimeGuardrailsService.class);

    private static final List<String> FORBIDDEN_SQL_KEYWORDS = Arrays.asList(
            "DROP", "DELETE", "TRUNCATE", "ALTER", "CREATE", "GRANT", "REVOKE",
            "INSERT", "UPDATE", "EXEC", "EXECUTE", "SHUTDOWN", "KILL"
    );

    private static final List<String> ALLOWED_DOMAINS = Arrays.asList(
            "jsonplaceholder.typicode.com",
            "reqres.in",
            "restcountries.com",
            "api.github.com"
    );

    public GuardrailModel.GuardrailResult validateSqlQuery(String sqlQuery) {
        log.debug("Validating SQL query");

        GuardrailModel.GuardrailResult result = new GuardrailModel.GuardrailResult();
        result.setAllowed(true);

        String upperSql = sqlQuery.toUpperCase().trim();

        // Only allow SELECT
        if (!upperSql.startsWith("SELECT")) {
            result.setAllowed(false);
            result.setViolation(createViolation(
                    GuardrailModel.ViolationType.DANGEROUS_SQL_KEYWORD,
                    "Only SELECT queries are allowed",
                    GuardrailModel.SeverityLevel.CRITICAL
            ));
            return result;
        }

        // Check forbidden keywords
        for (String keyword : FORBIDDEN_SQL_KEYWORDS) {
            if (upperSql.contains(keyword)) {
                result.setAllowed(false);
                result.setViolation(createViolation(
                        GuardrailModel.ViolationType.DANGEROUS_SQL_KEYWORD,
                        "SQL contains forbidden keyword: " + keyword,
                        GuardrailModel.SeverityLevel.CRITICAL
                ));
                return result;
            }
        }

        // Check multiple statements
        if (sqlQuery.contains(";") && sqlQuery.indexOf(";") != sqlQuery.length() - 1) {
            result.setAllowed(false);
            result.setViolation(createViolation(
                    GuardrailModel.ViolationType.SQL_INJECTION,
                    "Multiple SQL statements not allowed",
                    GuardrailModel.SeverityLevel.CRITICAL
            ));
            return result;
        }

        // Check comments
        if (upperSql.contains("--") || upperSql.contains("/*")) {
            result.setAllowed(false);
            result.setViolation(createViolation(
                    GuardrailModel.ViolationType.SQL_INJECTION,
                    "SQL comments not allowed",
                    GuardrailModel.SeverityLevel.HIGH
            ));
            return result;
        }

        // Check UNION
        if (upperSql.contains("UNION")) {
            result.setAllowed(false);
            result.setViolation(createViolation(
                    GuardrailModel.ViolationType.SQL_INJECTION,
                    "UNION keyword not allowed",
                    GuardrailModel.SeverityLevel.HIGH
            ));
            return result;
        }

        return result;
    }

    public GuardrailModel.GuardrailResult validateApiUrl(String url) {
        log.debug("Validating API URL: {}", url);

        GuardrailModel.GuardrailResult result = new GuardrailModel.GuardrailResult();
        result.setAllowed(true);

        try {
            URI uri = new URI(url);
            String host = uri.getHost();
            String scheme = uri.getScheme();

            // Check protocol
            if (!"https".equals(scheme) && !"http".equals(scheme)) {
                result.setAllowed(false);
                result.setViolation(createViolation(
                        GuardrailModel.ViolationType.UNAUTHORIZED_API_ACCESS,
                        "Invalid URL scheme: " + scheme,
                        GuardrailModel.SeverityLevel.HIGH
                ));
                return result;
            }

            // Check for internal network (SSRF prevention)
            if (isInternalNetwork(host)) {
                result.setAllowed(false);
                result.setViolation(createViolation(
                        GuardrailModel.ViolationType.SSRF_ATTEMPT,
                        "Cannot access internal network",
                        GuardrailModel.SeverityLevel.CRITICAL
                ));
                return result;
            }

            // Optional: Check whitelist
            boolean domainAllowed = ALLOWED_DOMAINS.stream()
                    .anyMatch(allowed -> host.endsWith(allowed));

            if (!domainAllowed) {
                log.warn("Domain not in whitelist: {}", host);
                // You can choose to block or just warn
                // For now, we'll allow but log
            }

        } catch (Exception e) {
            result.setAllowed(false);
            result.setViolation(createViolation(
                    GuardrailModel.ViolationType.UNAUTHORIZED_API_ACCESS,
                    "Invalid URL format",
                    GuardrailModel.SeverityLevel.HIGH
            ));
        }

        return result;
    }

    private boolean isInternalNetwork(String host) {
        return host.equals("localhost")
                || host.equals("127.0.0.1")
                || host.startsWith("192.168.")
                || host.startsWith("10.")
                || host.startsWith("172.16.")
                || host.startsWith("172.17.")
                || host.startsWith("172.18.")
                || host.startsWith("172.19.")
                || host.startsWith("172.20.")
                || host.startsWith("172.21.")
                || host.startsWith("172.22.")
                || host.startsWith("172.23.")
                || host.startsWith("172.24.")
                || host.startsWith("172.25.")
                || host.startsWith("172.26.")
                || host.startsWith("172.27.")
                || host.startsWith("172.28.")
                || host.startsWith("172.29.")
                || host.startsWith("172.30.")
                || host.startsWith("172.31.");
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