package com.chatbot.agent.runtime.graph;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A validated, immutable execution plan.
 *
 * <p><b>An ExecutionGraph that exists is well-formed.</b> Validation happens once, at construction:
 * unique ids, every edge resolving to a declared node, no self-edges, no cycles. Downstream code
 * never re-checks and cannot be handed a half-valid graph, which removes a whole category of
 * defensive checks from the scheduler.
 *
 * <p>Node ordering is preserved and deterministic throughout. Two runs of the same plan schedule
 * identically; without that, a failure that depends on ordering is irreproducible.
 *
 * @see <a href="../../../../../../../../docs/RUNTIME_DESIGN.md">RUNTIME_DESIGN.md</a>
 */
public final class ExecutionGraph {

    private final Map<String, ExecutionNode> nodes;
    private final List<ExecutionEdge> edges;
    private final Map<String, Set<String>> dependencies;   // node -> nodes it waits for
    private final Map<String, Set<String>> dependents;     // node -> nodes waiting on it
    private final List<String> topologicalOrder;

    public ExecutionGraph(Collection<ExecutionNode> nodes, Collection<ExecutionEdge> edges) {
        this.nodes = new LinkedHashMap<>();
        for (ExecutionNode n : nodes) {
            if (this.nodes.putIfAbsent(n.getId(), n) != null) {
                throw new GraphValidationException("Duplicate node id: '" + n.getId() + "'");
            }
        }
        if (this.nodes.isEmpty()) {
            throw new GraphValidationException("Graph must contain at least one node");
        }

        this.edges = List.copyOf(edges);
        this.dependencies = new HashMap<>();
        this.dependents = new HashMap<>();
        for (String id : this.nodes.keySet()) {
            dependencies.put(id, new LinkedHashSet<>());
            dependents.put(id, new LinkedHashSet<>());
        }

        for (ExecutionEdge e : this.edges) {
            if (!this.nodes.containsKey(e.from())) {
                throw new GraphValidationException(
                        "Edge " + e + " references unknown node '" + e.from() + "'");
            }
            if (!this.nodes.containsKey(e.to())) {
                throw new GraphValidationException(
                        "Edge " + e + " references unknown node '" + e.to() + "'");
            }
            if (e.from().equals(e.to())) {
                throw new GraphValidationException(
                        "Self-dependency on node '" + e.from() + "': a node cannot depend on itself");
            }
            dependencies.get(e.to()).add(e.from());
            dependents.get(e.from()).add(e.to());
        }

        this.topologicalOrder = topologicallySort();   // throws if a cycle exists
    }

    /**
     * Kahn's algorithm. Produces the execution order and detects cycles in the same pass: if fewer
     * nodes are emitted than exist, the remainder are exactly the nodes involved in, or downstream
     * of, a cycle.
     *
     * <p>Iterative rather than recursive DFS on purpose - a deep or adversarial graph must fail
     * validation, not overflow the JVM stack. A StackOverflowError here would turn a rejected input
     * into a crash.
     */
    private List<String> topologicallySort() {
        Map<String, Integer> inDegree = new LinkedHashMap<>();
        for (String id : nodes.keySet()) {
            inDegree.put(id, dependencies.get(id).size());
        }

        // Insertion order is preserved, so the emitted order is stable across runs.
        Deque<String> ready = new ArrayDeque<>();
        inDegree.forEach((id, deg) -> { if (deg == 0) ready.add(id); });

        List<String> order = new ArrayList<>(nodes.size());
        while (!ready.isEmpty()) {
            String id = ready.poll();
            order.add(id);
            for (String dep : dependents.get(id)) {
                if (inDegree.merge(dep, -1, Integer::sum) == 0) {
                    ready.add(dep);
                }
            }
        }

        if (order.size() != nodes.size()) {
            Set<String> remaining = new LinkedHashSet<>(nodes.keySet());
            remaining.removeAll(order);
            throw new GraphValidationException(
                    "Cycle detected. Nodes involved: " + remaining
                    + ". Cycle path: " + describeCycle(remaining));
        }
        return List.copyOf(order);
    }

    /**
     * Walk the unresolved remainder to name an actual cycle path.
     *
     * <p>"A cycle exists" is not actionable on a graph of any size; {@code A -> B -> C -> A} is.
     */
    private String describeCycle(Set<String> remaining) {
        for (String start : remaining) {
            List<String> path = new ArrayList<>();
            Set<String> onPath = new HashSet<>();
            String current = start;
            while (current != null && remaining.contains(current)) {
                if (!onPath.add(current)) {
                    int from = path.indexOf(current);
                    List<String> cycle = new ArrayList<>(path.subList(from, path.size()));
                    cycle.add(current);
                    return String.join(" -> ", cycle);
                }
                path.add(current);
                current = dependents.get(current).stream()
                        .filter(remaining::contains).findFirst().orElse(null);
            }
        }
        return "unresolved";
    }

    public ExecutionNode node(String id) {
        ExecutionNode n = nodes.get(id);
        if (n == null) {
            throw new IllegalArgumentException("No such node: '" + id + "'");
        }
        return n;
    }

    public Collection<ExecutionNode> nodes() { return nodes.values(); }
    public List<ExecutionEdge> edges() { return edges; }
    public int size() { return nodes.size(); }

    /** Ids this node waits for. */
    public Set<String> dependenciesOf(String nodeId) {
        return Set.copyOf(dependencies.getOrDefault(nodeId, Set.of()));
    }

    /** Ids waiting on this node. */
    public Set<String> dependentsOf(String nodeId) {
        return Set.copyOf(dependents.getOrDefault(nodeId, Set.of()));
    }

    /** Nodes with no dependencies - the initial frontier. */
    public List<String> roots() {
        return nodes.keySet().stream().filter(id -> dependencies.get(id).isEmpty()).toList();
    }

    /** A valid execution order. Stable for a given graph. */
    public List<String> topologicalOrder() { return topologicalOrder; }

    /**
     * Every node reachable downstream of the given node, transitively.
     *
     * <p>Used when a node fails terminally: this is the set to mark SKIPPED. Computing it by
     * traversal rather than by repeated re-scanning keeps failure handling O(affected subgraph)
     * instead of O(graph) per failure.
     */
    public Set<String> transitiveDependentsOf(String nodeId) {
        Set<String> seen = new LinkedHashSet<>();
        Deque<String> stack = new ArrayDeque<>(dependents.getOrDefault(nodeId, Set.of()));
        while (!stack.isEmpty()) {
            String id = stack.pop();
            if (seen.add(id)) {
                stack.addAll(dependents.getOrDefault(id, Set.of()));
            }
        }
        return seen;
    }

    @Override
    public String toString() {
        return "ExecutionGraph[nodes=" + nodes.size() + ", edges=" + edges.size() + "]";
    }
}
