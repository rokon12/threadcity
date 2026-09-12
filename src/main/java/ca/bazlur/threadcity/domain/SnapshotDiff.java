package ca.bazlur.threadcity.domain;

import java.util.List;
import java.util.Objects;

/**
 * Deterministic difference between two chronologically adjacent snapshots.
 */
public record SnapshotDiff(
        AnalysisResult before,
        AnalysisResult after,
        List<ThreadChange> threadChanges,
        int newWaitEdges,
        int resolvedWaitEdges,
        int persistentWaitEdges,
        int newDeadlocks,
        int resolvedDeadlocks) {

    public SnapshotDiff {
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");
        threadChanges = List.copyOf(threadChanges);
        if (newWaitEdges < 0 || resolvedWaitEdges < 0 || persistentWaitEdges < 0
                || newDeadlocks < 0 || resolvedDeadlocks < 0) {
            throw new IllegalArgumentException("Snapshot diff counts cannot be negative");
        }
    }

    public long changedThreadCount() {
        return threadChanges.stream().filter(ThreadChange::changed).count();
    }
}
