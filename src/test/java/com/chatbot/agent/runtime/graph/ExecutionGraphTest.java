package com.chatbot.agent.runtime.graph;

import com.chatbot.agent.model.ToolModel.SideEffect;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Graph construction and validation. The central property under test is that an ExecutionGraph
 * which exists is well-formed, so nothing downstream has to re-check it.
 */
class ExecutionGraphTest {

    private static ExecutionNode n(String id) {
        return ExecutionNode.builder(id).tool("t").sideEffect(SideEffect.READ_ONLY).build();
    }

    private static ExecutionEdge e(String from, String to) {
        return new ExecutionEdge(from, to);
    }

    @Nested
    @DisplayName("valid graphs")
    class Valid {

        @Test
        @DisplayName("A -> B -> C: linear chain orders correctly")
        void linearChain() {
            var g = new ExecutionGraph(List.of(n("A"), n("B"), n("C")),
                    List.of(e("A", "B"), e("B", "C")));
            assertEquals(List.of("A", "B", "C"), g.topologicalOrder());
            assertEquals(List.of("A"), g.roots());
            assertEquals(java.util.Set.of("A"), g.dependenciesOf("B"));
            assertEquals(java.util.Set.of("C"), g.dependentsOf("B"));
        }

        @Test
        @DisplayName("A -> [B,C] -> D: fan-out and fan-in")
        void diamond() {
            var g = new ExecutionGraph(List.of(n("A"), n("B"), n("C"), n("D")),
                    List.of(e("A", "B"), e("A", "C"), e("B", "D"), e("C", "D")));

            var order = g.topologicalOrder();
            assertEquals("A", order.get(0), "the only root must come first");
            assertEquals("D", order.get(3), "the sink must come last");
            assertTrue(order.indexOf("B") < order.indexOf("D"));
            assertTrue(order.indexOf("C") < order.indexOf("D"));
            assertEquals(java.util.Set.of("B", "C"), g.dependenciesOf("D"));
        }

        @Test
        @DisplayName("independent nodes are all roots")
        void independentNodes() {
            var g = new ExecutionGraph(List.of(n("A"), n("B"), n("C")), List.of());
            assertEquals(List.of("A", "B", "C"), g.roots());
        }

        @Test
        @DisplayName("ordering is deterministic across constructions of the same graph")
        void orderingIsStable() {
            var first = new ExecutionGraph(List.of(n("A"), n("B"), n("C"), n("D")),
                    List.of(e("A", "B"), e("A", "C"), e("B", "D"), e("C", "D")))
                    .topologicalOrder();
            for (int i = 0; i < 50; i++) {
                var again = new ExecutionGraph(List.of(n("A"), n("B"), n("C"), n("D")),
                        List.of(e("A", "B"), e("A", "C"), e("B", "D"), e("C", "D")))
                        .topologicalOrder();
                assertEquals(first, again, "scheduling order must be reproducible");
            }
        }

        @Test
        @DisplayName("transitive dependents are found for skip propagation")
        void transitiveDependents() {
            var g = new ExecutionGraph(List.of(n("A"), n("B"), n("C"), n("D"), n("E")),
                    List.of(e("A", "B"), e("B", "C"), e("C", "D"), e("A", "E")));
            assertEquals(java.util.Set.of("B", "C", "D"), g.transitiveDependentsOf("A")
                    .stream().filter(x -> !x.equals("E")).collect(java.util.stream.Collectors.toSet()));
            assertTrue(g.transitiveDependentsOf("A").contains("E"));
            assertTrue(g.transitiveDependentsOf("D").isEmpty());
        }
    }

    @Nested
    @DisplayName("rejected graphs")
    class Invalid {

        @Test
        @DisplayName("A -> B -> A is rejected, and the error names the cycle")
        void simpleCycleRejected() {
            var ex = assertThrows(GraphValidationException.class, () ->
                    new ExecutionGraph(List.of(n("A"), n("B")), List.of(e("A", "B"), e("B", "A"))));
            assertTrue(ex.getMessage().contains("Cycle"), ex.getMessage());
            assertTrue(ex.getMessage().contains("A") && ex.getMessage().contains("B"));
            assertTrue(ex.getMessage().contains("->"), "must name the path, not just say a cycle exists");
        }

        @Test
        @DisplayName("a longer cycle is rejected")
        void longCycleRejected() {
            var ex = assertThrows(GraphValidationException.class, () ->
                    new ExecutionGraph(List.of(n("A"), n("B"), n("C"), n("D")),
                            List.of(e("A", "B"), e("B", "C"), e("C", "D"), e("D", "B"))));
            assertTrue(ex.getMessage().contains("Cycle"));
        }

        @Test
        @DisplayName("a self-edge is rejected")
        void selfEdgeRejected() {
            var ex = assertThrows(GraphValidationException.class, () ->
                    new ExecutionGraph(List.of(n("A")), List.of(e("A", "A"))));
            assertTrue(ex.getMessage().contains("Self-dependency"), ex.getMessage());
        }

        @Test
        @DisplayName("an edge to an undeclared node is rejected")
        void unknownNodeRejected() {
            var ex = assertThrows(GraphValidationException.class, () ->
                    new ExecutionGraph(List.of(n("A"), n("B")), List.of(e("A", "Z"))));
            assertTrue(ex.getMessage().contains("unknown node"), ex.getMessage());
            assertTrue(ex.getMessage().contains("Z"));
        }

        @Test
        void duplicateNodeIdRejected() {
            var ex = assertThrows(GraphValidationException.class, () ->
                    new ExecutionGraph(List.of(n("A"), n("A")), List.of()));
            assertTrue(ex.getMessage().contains("Duplicate"), ex.getMessage());
        }

        @Test
        void emptyGraphRejected() {
            assertThrows(GraphValidationException.class,
                    () -> new ExecutionGraph(List.of(), List.of()));
        }

        @Test
        @DisplayName("a deep graph fails validation rather than overflowing the stack")
        void deepGraphDoesNotOverflowStack() {
            int depth = 20_000;
            List<ExecutionNode> nodes = new ArrayList<>();
            List<ExecutionEdge> edges = new ArrayList<>();
            for (int i = 0; i < depth; i++) {
                nodes.add(n("n" + i));
                if (i > 0) edges.add(e("n" + (i - 1), "n" + i));
            }
            // Valid but very deep: recursion would blow the stack here.
            var g = assertDoesNotThrow(() -> new ExecutionGraph(nodes, edges));
            assertEquals(depth, g.size());

            // And a deep graph WITH a cycle must be rejected, not crash.
            edges.add(e("n" + (depth - 1), "n0"));
            assertThrows(GraphValidationException.class, () -> new ExecutionGraph(nodes, edges));
        }
    }

    @Nested
    @DisplayName("idempotency key enforcement")
    class IdempotencyKeys {

        @Test
        @DisplayName("a side-effecting node without an idempotency key is rejected at construction")
        void sideEffectingNodeRequiresKey() {
            for (SideEffect effect : List.of(SideEffect.REVERSIBLE_WRITE,
                    SideEffect.IRREVERSIBLE_WRITE, SideEffect.PRIVILEGED)) {
                var ex = assertThrows(GraphValidationException.class, () ->
                        ExecutionNode.builder("x").tool("t").sideEffect(effect).build(),
                        effect + " must require an idempotency key");
                assertTrue(ex.getMessage().contains("idempotency key"), ex.getMessage());
            }
        }

        @Test
        void readOnlyNodeMayOmitKey() {
            assertDoesNotThrow(() ->
                    ExecutionNode.builder("x").tool("t").sideEffect(SideEffect.READ_ONLY).build());
        }

        @Test
        void sideEffectingNodeWithKeyIsAccepted() {
            var node = ExecutionNode.builder("x").tool("t")
                    .sideEffect(SideEffect.IRREVERSIBLE_WRITE)
                    .idempotencyKey("k-1").build();
            assertEquals("k-1", node.getIdempotencyKey());
        }

        @Test
        @DisplayName("an unspecified side-effect class defaults to PRIVILEGED, so a key is required")
        void defaultsToPrivileged() {
            assertThrows(GraphValidationException.class,
                    () -> ExecutionNode.builder("x").tool("t").build());
        }
    }
}
