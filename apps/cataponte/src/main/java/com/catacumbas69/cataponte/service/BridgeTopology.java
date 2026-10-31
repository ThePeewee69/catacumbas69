package com.catacumbas69.cataponte.service;

import com.catacumbas69.cataponte.model.BridgeDocument;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Detects message loops between bridges (ADR 0005).
 *
 * <p>Enabled bridges form a directed graph: each bridge is an edge from its source topic to each of its
 * destination topics. A cycle in that graph (A→B→A, A→B→C→A, …) would relay the same messages forever.
 * Partitions are folded into their topic ({@code orders-partition-3} → {@code orders}) so a bridge on a
 * partition and one on the whole topic are treated as the same node.
 */
public final class BridgeTopology {

    private static final Pattern PARTITION_SUFFIX = Pattern.compile("-partition-\\d+$");

    private BridgeTopology() {
    }

    /**
     * Checks whether enabling {@code candidate} next to {@code others} would close a loop.
     *
     * @param candidate the bridge being created, changed or enabled
     * @param others every other bridge; disabled ones and {@code candidate}'s own stored copy are ignored
     * @return the loop as a readable path, or empty when there is none
     */
    public static Optional<String> loopThrough(BridgeDocument candidate, Collection<BridgeDocument> others) {
        Map<String, List<Edge>> graph = graphOf(others.stream()
                .filter(BridgeDocument::isEnabled)
                .filter(bridge -> !Objects.equals(bridge.getId(), candidate.getId()))
                .toList());
        String source = node(candidate.getSource().getTopic());
        for (String destination : candidate.resolveDestinationTopics()) {
            Optional<List<Edge>> back = path(graph, node(destination), source);
            if (back.isPresent()) {
                return Optional.of(describe(candidate, destination, back.get()));
            }
        }
        return Optional.empty();
    }

    /**
     * Returns the ids of the bridges that are part of a loop among {@code enabled}. Used by the
     * reconciler, which must not start them even if they were written to MongoDB directly.
     *
     * @param enabled enabled bridges
     * @return ids of bridges on a loop
     */
    public static Set<String> bridgesInLoops(Collection<BridgeDocument> enabled) {
        Map<String, List<Edge>> graph = graphOf(enabled);
        Set<String> looping = new HashSet<>();
        for (BridgeDocument bridge : enabled) {
            String source = node(bridge.getSource().getTopic());
            for (String destination : bridge.resolveDestinationTopics()) {
                if (path(graph, node(destination), source).isPresent()) {
                    looping.add(bridge.getId());
                }
            }
        }
        return looping;
    }

    /** Topic name with any partition suffix removed. */
    static String node(String topic) {
        return PARTITION_SUFFIX.matcher(topic).replaceFirst("");
    }

    private static Map<String, List<Edge>> graphOf(Collection<BridgeDocument> bridges) {
        Map<String, List<Edge>> graph = new HashMap<>();
        for (BridgeDocument bridge : bridges) {
            if (bridge.getSource() == null || bridge.getSource().getTopic() == null) {
                continue;
            }
            String source = node(bridge.getSource().getTopic());
            for (String destination : bridge.resolveDestinationTopics()) {
                graph.computeIfAbsent(source, key -> new ArrayList<>()).add(new Edge(bridge, node(destination)));
            }
        }
        return graph;
    }

    /** Breadth-first search; returns the edges from {@code from} to {@code to}, shortest first. */
    private static Optional<List<Edge>> path(Map<String, List<Edge>> graph, String from, String to) {
        if (from.equals(to)) {
            return Optional.of(List.of());
        }
        Map<String, Edge> reachedBy = new HashMap<>();
        Deque<String> queue = new ArrayDeque<>(List.of(from));
        Set<String> visited = new HashSet<>(Set.of(from));
        while (!queue.isEmpty()) {
            String topic = queue.poll();
            for (Edge edge : graph.getOrDefault(topic, List.of())) {
                if (!visited.add(edge.to())) {
                    continue;
                }
                reachedBy.put(edge.to(), edge);
                if (edge.to().equals(to)) {
                    return Optional.of(backtrack(reachedBy, from, to));
                }
                queue.add(edge.to());
            }
        }
        return Optional.empty();
    }

    private static List<Edge> backtrack(Map<String, Edge> reachedBy, String from, String to) {
        List<Edge> edges = new ArrayList<>();
        String topic = to;
        while (!topic.equals(from)) {
            Edge edge = reachedBy.get(topic);
            edges.add(0, edge);
            topic = node(edge.bridge().getSource().getTopic());
        }
        return edges;
    }

    private static String describe(BridgeDocument candidate, String destination, List<Edge> back) {
        StringBuilder text = new StringBuilder(candidate.getSource().getTopic())
                .append(" → ").append(destination).append(" ('").append(candidate.getName()).append("')");
        back.forEach(edge -> text.append(" → ").append(edge.to())
                .append(" ('").append(edge.bridge().getName()).append("')"));
        return text.toString();
    }

    private record Edge(BridgeDocument bridge, String to) {
    }
}
