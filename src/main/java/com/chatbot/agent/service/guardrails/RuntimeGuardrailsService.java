package com.chatbot.agent.service.guardrails;

import com.chatbot.agent.model.GuardrailModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import com.chatbot.agent.config.ToolExecutionProperties;
import com.chatbot.agent.service.policy.SsrfGuard;

import java.net.URI;
import java.util.Arrays;
import java.util.List;

@Service
public class RuntimeGuardrailsService {

    private static final Logger log = LoggerFactory.getLogger(RuntimeGuardrailsService.class);

    private final SsrfGuard ssrfGuard;

    public RuntimeGuardrailsService(ToolExecutionProperties config) {
        this.ssrfGuard = new SsrfGuard(
                config.getSecurity().isEnforceOutboundAllowlist(),
                config.getSecurity().getAllowedOutboundHosts());
    }

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

    /**
     * Delegates to {@link SsrfGuard}, which parses the URL and inspects every resolved address.
     * The previous implementation compared host strings, missed the cloud metadata endpoint
     * entirely, and computed an allowlist it then ignored.
     */
    public GuardrailModel.GuardrailResult validateApiUrl(String url) {
        GuardrailModel.GuardrailResult result = new GuardrailModel.GuardrailResult();
        SsrfGuard.Verdict verdict = ssrfGuard.check(url);

        result.setAllowed(verdict.allowed());
        if (!verdict.allowed()) {
            GuardrailModel.ViolationType type = switch (verdict.reason()) {
                case "PRIVATE_ADDRESS", "METADATA_ENDPOINT" -> GuardrailModel.ViolationType.SSRF_ATTEMPT;
                default -> GuardrailModel.ViolationType.UNAUTHORIZED_API_ACCESS;
            };
            result.setViolation(createViolation(type, verdict.detail(),
                    GuardrailModel.SeverityLevel.CRITICAL));
        }
        return result;
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