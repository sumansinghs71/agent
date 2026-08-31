package com.chatbot.agent.service.policy;

import com.chatbot.agent.model.ToolModel;
import com.chatbot.agent.model.ToolModel.SideEffect;
import com.chatbot.agent.security.InvocationPrincipal;
import com.chatbot.agent.security.Roles;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The authority gate. These tests encode the rule that the model proposes and the runtime decides.
 */
class ToolInvocationPolicyTest {

    private final ToolInvocationPolicy policy = new ToolInvocationPolicy();

    private static ToolModel.Tool tool(ToolModel.FunctionType type) {
        ToolModel.Tool t = new ToolModel.Tool();
        t.setFuncNameKey("t1");
        t.setChatbotId(1L);
        t.setFunctionType(type);
        return t;
    }

    private static ToolModel.Tool readOnlySql() {
        ToolModel.Tool t = tool(ToolModel.FunctionType.SQL);
        t.setSqlQuery("SELECT 1");
        return t;
    }

    private static ToolModel.ToolParameter param(String name, boolean required) {
        ToolModel.ToolParameter p = new ToolModel.ToolParameter();
        p.setParamNameKey(name);
        p.setRequired(required);
        return p;
    }

    @Nested
    @DisplayName("side-effect derivation")
    class Classification {

        @Test
        void sqlSelectIsReadOnly() {
            assertEquals(SideEffect.READ_ONLY, policy.classify(readOnlySql()));
        }

        @Test
        void sqlNonSelectIsIrreversible() {
            ToolModel.Tool t = tool(ToolModel.FunctionType.SQL);
            t.setSqlQuery("DELETE FROM employees");
            assertEquals(SideEffect.IRREVERSIBLE_WRITE, policy.classify(t));
        }

        @Test
        void restGetIsReadOnlyAndOtherMethodsAreNot() {
            ToolModel.Tool get = tool(ToolModel.FunctionType.REST);
            get.setHttpMethod("GET");
            assertEquals(SideEffect.READ_ONLY, policy.classify(get));

            ToolModel.Tool post = tool(ToolModel.FunctionType.REST);
            post.setHttpMethod("POST");
            assertEquals(SideEffect.IRREVERSIBLE_WRITE, policy.classify(post));
        }

        @Test
        @DisplayName("code execution is PRIVILEGED - its blast radius is not statically bounded")
        void codeToolsArePrivileged() {
            assertEquals(SideEffect.PRIVILEGED, policy.classify(tool(ToolModel.FunctionType.PYTHON)));
            assertEquals(SideEffect.PRIVILEGED, policy.classify(tool(ToolModel.FunctionType.JAVASCRIPT)));
        }

        @Test
        @DisplayName("an explicit declaration overrides derivation")
        void declaredWins() {
            ToolModel.Tool t = tool(ToolModel.FunctionType.PYTHON);
            t.setSideEffect(SideEffect.READ_ONLY);
            assertEquals(SideEffect.READ_ONLY, policy.classify(t));
        }

        @Test
        @DisplayName("an unclassifiable tool is treated as dangerous, not as safe")
        void unknownShapeFailsClosed() {
            ToolModel.Tool t = new ToolModel.Tool();
            t.setFuncNameKey("mystery");
            assertEquals(SideEffect.PRIVILEGED, policy.classify(t));
        }
    }

    @Nested
    @DisplayName("denials")
    class Denials {

        @Test
        void unknownToolIsDeniedByPolicyNotByLookupFailure() {
            PolicyDecision d = policy.evaluate(null, "ghost", 1L,
                    InvocationPrincipal.of("admin", Roles.ROLE_ADMIN), Map.of());
            assertFalse(d.allowed());
            assertEquals(PolicyDecision.UNKNOWN_TOOL, d.reason());
        }

        @Test
        void disabledToolIsDenied() {
            ToolModel.Tool t = readOnlySql();
            t.setEnabled(false);
            PolicyDecision d = policy.evaluate(t, "t1", 1L,
                    InvocationPrincipal.of("admin", Roles.ROLE_ADMIN), Map.of());
            assertFalse(d.allowed());
            assertEquals(PolicyDecision.TOOL_DISABLED, d.reason());
        }

        @Test
        @DisplayName("a tool belonging to another tenant is denied")
        void tenantMismatchIsDenied() {
            ToolModel.Tool t = readOnlySql();
            t.setChatbotId(1L);
            PolicyDecision d = policy.evaluate(t, "t1", 999L,
                    InvocationPrincipal.of("admin", Roles.ROLE_ADMIN), Map.of());
            assertFalse(d.allowed());
            assertEquals(PolicyDecision.TENANT_MISMATCH, d.reason());
        }

        @Test
        void anonymousIsDenied() {
            PolicyDecision d = policy.evaluate(readOnlySql(), "t1", 1L,
                    InvocationPrincipal.anonymous(), Map.of());
            assertFalse(d.allowed());
            assertEquals(PolicyDecision.NOT_AUTHENTICATED, d.reason());
        }

        @Test
        @DisplayName("the internal system principal carries no authority")
        void systemPrincipalCannotInvoke() {
            PolicyDecision d = policy.evaluate(readOnlySql(), "t1", 1L,
                    InvocationPrincipal.system(), Map.of());
            assertFalse(d.allowed());
            assertEquals(PolicyDecision.NOT_AUTHENTICATED, d.reason());
        }

        @Test
        @DisplayName("ROLE_USER may not run code tools")
        void userCannotInvokePrivileged() {
            PolicyDecision d = policy.evaluate(tool(ToolModel.FunctionType.PYTHON), "t1", 1L,
                    InvocationPrincipal.of("bob", Roles.ROLE_USER), Map.of());
            assertFalse(d.allowed());
            assertEquals(PolicyDecision.INSUFFICIENT_AUTHORITY, d.reason());
        }

        @Test
        @DisplayName("ROLE_OPERATOR may not run code tools either")
        void operatorCannotInvokePrivileged() {
            PolicyDecision d = policy.evaluate(tool(ToolModel.FunctionType.PYTHON), "t1", 1L,
                    InvocationPrincipal.of("op", Roles.ROLE_USER, Roles.ROLE_OPERATOR), Map.of());
            assertFalse(d.allowed());
            assertEquals(PolicyDecision.INSUFFICIENT_AUTHORITY, d.reason());
        }

        @Test
        @DisplayName("irreversible effects are refused while no approval mechanism exists")
        void irreversibleRequiresApproval() {
            ToolModel.Tool t = tool(ToolModel.FunctionType.SQL);
            t.setSqlQuery("DROP TABLE employees");
            PolicyDecision d = policy.evaluate(t, "t1", 1L,
                    InvocationPrincipal.of("admin", Roles.ROLE_ADMIN), Map.of());
            assertFalse(d.allowed());
            assertEquals(PolicyDecision.APPROVAL_REQUIRED, d.reason());
        }

        @Test
        @DisplayName("arguments the tool never declared are rejected")
        void undeclaredArgumentsRejected() {
            ToolModel.Tool t = readOnlySql();
            t.setParams(List.of(param("id", true)));
            PolicyDecision d = policy.evaluate(t, "t1", 1L,
                    InvocationPrincipal.of("bob", Roles.ROLE_USER),
                    Map.of("id", "1", "__proto__", "x"));
            assertFalse(d.allowed());
            assertEquals(PolicyDecision.SCHEMA_VIOLATION, d.reason());
        }

        @Test
        void missingRequiredArgumentRejected() {
            ToolModel.Tool t = readOnlySql();
            t.setParams(List.of(param("id", true)));
            PolicyDecision d = policy.evaluate(t, "t1", 1L,
                    InvocationPrincipal.of("bob", Roles.ROLE_USER), Map.of());
            assertFalse(d.allowed());
            assertEquals(PolicyDecision.SCHEMA_VIOLATION, d.reason());
        }

        @Test
        void argumentsSuppliedToAParameterlessToolRejected() {
            PolicyDecision d = policy.evaluate(readOnlySql(), "t1", 1L,
                    InvocationPrincipal.of("bob", Roles.ROLE_USER), Map.of("surprise", "1"));
            assertFalse(d.allowed());
            assertEquals(PolicyDecision.SCHEMA_VIOLATION, d.reason());
        }
    }

    @Nested
    @DisplayName("allows")
    class Allows {

        @Test
        void userMayInvokeReadOnly() {
            PolicyDecision d = policy.evaluate(readOnlySql(), "t1", 1L,
                    InvocationPrincipal.of("bob", Roles.ROLE_USER), Map.of());
            assertTrue(d.allowed(), d.detail());
            assertEquals(SideEffect.READ_ONLY, d.sideEffect());
        }

        @Test
        void adminMayInvokePrivileged() {
            PolicyDecision d = policy.evaluate(tool(ToolModel.FunctionType.PYTHON), "t1", 1L,
                    InvocationPrincipal.of("admin", Roles.ROLE_ADMIN), Map.of());
            assertTrue(d.allowed(), d.detail());
        }

        @Test
        void nullEnabledIsTreatedAsEnabledForBackwardCompatibility() {
            ToolModel.Tool t = readOnlySql();
            t.setEnabled(null);
            assertTrue(policy.evaluate(t, "t1", 1L,
                    InvocationPrincipal.of("bob", Roles.ROLE_USER), Map.of()).allowed());
        }

        @Test
        void declaredOptionalArgumentMayBeOmitted() {
            ToolModel.Tool t = readOnlySql();
            t.setParams(List.of(param("id", false)));
            assertTrue(policy.evaluate(t, "t1", 1L,
                    InvocationPrincipal.of("bob", Roles.ROLE_USER), Map.of()).allowed());
        }
    }
}
