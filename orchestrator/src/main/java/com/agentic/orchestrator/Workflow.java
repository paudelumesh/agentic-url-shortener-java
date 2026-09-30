package com.agentic.orchestrator;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

public class Workflow {
    public final String name;
    public final Map<String, StageNode> nodes;

    public Workflow(String name, List<StageNode> nodes) {
        this.name = name;
        Map<String, StageNode> map = new LinkedHashMap<>();
        for (StageNode n : nodes) {
            map.put(n.id, n);
        }
        this.nodes = map;
        validate();
    }

    private void validate() {
        for (StageNode node : nodes.values()) {
            for (String dep : node.dependsOn) {
                if (!nodes.containsKey(dep)) {
                    throw new IllegalArgumentException(node.id + " depends on unknown node " + dep);
                }
            }
        }
        Map<String, Integer> color = new LinkedHashMap<>();
        for (String id : nodes.keySet()) {
            color.put(id, 0);
        }
        for (String id : nodes.keySet()) {
            if (color.get(id) == 0) {
                visit(id, color);
            }
        }
    }

    private void visit(String nodeId, Map<String, Integer> color) {
        color.put(nodeId, 1);
        for (String dep : nodes.get(nodeId).dependsOn) {
            int c = color.get(dep);
            if (c == 1) {
                throw new CycleException("cycle detected involving " + nodeId + " -> " + dep);
            }
            if (c == 0) {
                visit(dep, color);
            }
        }
        color.put(nodeId, 2);
    }

    public List<String> dependentsOf(String nodeId) {
        List<String> out = new ArrayList<>();
        for (StageNode n : nodes.values()) {
            if (n.dependsOn.contains(nodeId)) {
                out.add(n.id);
            }
        }
        return out;
    }

    public Set<String> allDownstream(String nodeId) {
        Set<String> downstream = new TreeSet<>();
        Deque<String> frontier = new ArrayDeque<>();
        frontier.push(nodeId);
        while (!frontier.isEmpty()) {
            String current = frontier.pop();
            for (String depId : dependentsOf(current)) {
                if (downstream.add(depId)) {
                    frontier.push(depId);
                }
            }
        }
        return downstream;
    }
}
