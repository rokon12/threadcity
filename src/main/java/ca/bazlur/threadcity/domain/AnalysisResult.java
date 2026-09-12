package ca.bazlur.threadcity.domain;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public record AnalysisResult(
        ThreadSnapshot snapshot,
        List<WaitEdge> waitEdges,
        List<DeadlockCycle> deadlocks,
        List<StackCluster> stackClusters,
        List<Finding> findings) {

    public AnalysisResult {
        waitEdges = List.copyOf(waitEdges);
        deadlocks = List.copyOf(deadlocks);
        stackClusters = List.copyOf(stackClusters);
        findings = List.copyOf(findings);
    }

    public Map<ThreadState, Long> stateCounts() {
        Map<ThreadState, Long> counts = new EnumMap<>(ThreadState.class);
        for (ThreadState state : ThreadState.values()) {
            counts.put(state, 0L);
        }
        snapshot.threads().forEach(thread -> counts.compute(thread.state(), (state, count) -> count + 1));
        return counts;
    }

    public boolean hasDeadlock() {
        return !deadlocks.isEmpty();
    }
}
