package ca.bazlur.threadcity.domain;

import java.util.List;

public record DeadlockCycle(List<WaitEdge> edges) {

    public DeadlockCycle {
        edges = List.copyOf(edges);
        if (edges.isEmpty()) {
            throw new IllegalArgumentException("A deadlock cycle must contain at least one edge");
        }
    }

    public List<JavaThread> threads() {
        return edges.stream().map(WaitEdge::waiter).toList();
    }
}
