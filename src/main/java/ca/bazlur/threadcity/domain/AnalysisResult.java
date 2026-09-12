package ca.bazlur.threadcity.domain;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public record AnalysisResult(
        ThreadSnapshot snapshot,
        List<WaitEdge> waitEdges,
        List<DeadlockCycle> deadlocks,
        List<BlockingImpact> blockingImpacts,
        List<SynchronizerInsight> synchronizers,
        List<StackCohort> stackCohorts,
        List<MethodHotspot> methodHotspots,
        List<StackCluster> stackClusters,
        List<IncidentPattern> patterns,
        List<Finding> findings) {

    public AnalysisResult {
        waitEdges = List.copyOf(waitEdges);
        deadlocks = List.copyOf(deadlocks);
        blockingImpacts = List.copyOf(blockingImpacts);
        synchronizers = List.copyOf(synchronizers);
        stackCohorts = List.copyOf(stackCohorts);
        methodHotspots = List.copyOf(methodHotspots);
        stackClusters = List.copyOf(stackClusters);
        patterns = List.copyOf(patterns);
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
