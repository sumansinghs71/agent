package com.chatbot.agent.runtime.state;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The node lifecycle. Every legal transition is asserted, and - more importantly - the complement
 * is asserted too: for each state, every transition NOT declared legal must throw.
 */
class NodeStateMachineTest {

    @ParameterizedTest
    @EnumSource(NodeState.class)
    @DisplayName("every transition not explicitly allowed is rejected")
    void complementOfAllowedTransitionsIsRejected(NodeState from) {
        Set<NodeState> allowed = from.allowedTransitions();
        for (NodeState to : NodeState.values()) {
            if (allowed.contains(to)) {
                assertDoesNotThrow(() -> from.transitionTo(to, "n1"), from + " -> " + to);
            } else {
                assertThrows(IllegalStateTransitionException.class,
                        () -> from.transitionTo(to, "n1"), from + " -> " + to + " must be rejected");
            }
        }
    }

    @ParameterizedTest
    @EnumSource(value = NodeState.class,
            names = {"SUCCEEDED", "FAILED_TERMINAL", "CANCELLED", "SKIPPED"})
    @DisplayName("terminal states have no outgoing transitions at all")
    void terminalStatesAreAbsorbing(NodeState terminal) {
        assertTrue(terminal.isTerminal());
        assertTrue(terminal.allowedTransitions().isEmpty());
        for (NodeState to : NodeState.values()) {
            assertThrows(IllegalStateTransitionException.class, () -> terminal.transitionTo(to, "n1"));
        }
    }

    @ParameterizedTest
    @EnumSource(value = NodeState.class,
            names = {"PENDING", "READY", "RUNNING", "WAITING_APPROVAL", "FAILED_RETRYABLE"})
    void nonTerminalStatesAreNotTerminal(NodeState s) {
        assertFalse(s.isTerminal());
        assertFalse(s.allowedTransitions().isEmpty());
    }

    @Test
    @DisplayName("the happy path is reachable end to end")
    void happyPath() {
        NodeState s = NodeState.PENDING;
        s = s.transitionTo(NodeState.READY, "n");
        s = s.transitionTo(NodeState.RUNNING, "n");
        s = s.transitionTo(NodeState.SUCCEEDED, "n");
        assertTrue(s.isTerminal());
    }

    @Test
    @DisplayName("a retry loop is reachable and eventually terminal")
    void retryLoop() {
        NodeState s = NodeState.RUNNING.transitionTo(NodeState.FAILED_RETRYABLE, "n");
        s = s.transitionTo(NodeState.READY, "n");
        s = s.transitionTo(NodeState.RUNNING, "n");
        s = s.transitionTo(NodeState.FAILED_RETRYABLE, "n");
        s = s.transitionTo(NodeState.FAILED_TERMINAL, "n");
        assertTrue(s.isTerminal());
    }

    @Test
    @DisplayName("approval parks and resumes")
    void approvalPath() {
        NodeState s = NodeState.RUNNING.transitionTo(NodeState.WAITING_APPROVAL, "n");
        assertEquals(NodeState.READY, s.transitionTo(NodeState.READY, "n"));
        assertEquals(NodeState.FAILED_TERMINAL, s.transitionTo(NodeState.FAILED_TERMINAL, "n"));
    }

    @Test
    @DisplayName("a terminally failed node cannot be resurrected - retrying means a new run")
    void terminalFailureCannotBeRetried() {
        assertThrows(IllegalStateTransitionException.class,
                () -> NodeState.FAILED_TERMINAL.transitionTo(NodeState.READY, "n"));
        assertThrows(IllegalStateTransitionException.class,
                () -> NodeState.FAILED_TERMINAL.transitionTo(NodeState.RUNNING, "n"));
    }

    @Test
    @DisplayName("a node cannot go straight from PENDING to RUNNING, bypassing readiness")
    void pendingCannotJumpToRunning() {
        assertThrows(IllegalStateTransitionException.class,
                () -> NodeState.PENDING.transitionTo(NodeState.RUNNING, "n"));
    }

    @Test
    @DisplayName("approval is reached before the effect, never after success")
    void cannotSeekApprovalAfterSucceeding() {
        assertThrows(IllegalStateTransitionException.class,
                () -> NodeState.SUCCEEDED.transitionTo(NodeState.WAITING_APPROVAL, "n"));
    }

    @Test
    void exceptionNamesTheNodeAndBothStates() {
        var e = assertThrows(IllegalStateTransitionException.class,
                () -> NodeState.SUCCEEDED.transitionTo(NodeState.RUNNING, "checkout-charge"));
        assertTrue(e.getMessage().contains("checkout-charge"));
        assertTrue(e.getMessage().contains("SUCCEEDED"));
        assertTrue(e.getMessage().contains("RUNNING"));
        assertEquals(NodeState.SUCCEEDED, e.getFrom());
        assertEquals(NodeState.RUNNING, e.getTo());
    }

    @Test
    @DisplayName("every state can reach a terminal state - no state is a dead end")
    void everyStateCanTerminate() {
        for (NodeState s : NodeState.values()) {
            if (s.isTerminal()) continue;
            Set<NodeState> seen = EnumSet.of(s);
            var frontier = EnumSet.of(s);
            boolean reachedTerminal = false;
            while (!frontier.isEmpty() && !reachedTerminal) {
                var next = EnumSet.noneOf(NodeState.class);
                for (NodeState f : frontier) {
                    for (NodeState t : f.allowedTransitions()) {
                        if (t.isTerminal()) { reachedTerminal = true; break; }
                        if (seen.add(t)) next.add(t);
                    }
                }
                frontier = next;
            }
            assertTrue(reachedTerminal, s + " cannot reach any terminal state");
        }
    }
}
