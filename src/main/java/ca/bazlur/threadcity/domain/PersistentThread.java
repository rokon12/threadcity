package ca.bazlur.threadcity.domain;

import java.util.Objects;

/**
 * A thread observed at the same potentially stalled execution point across a snapshot series.
 */
public record PersistentThread(
        String threadName,
        ThreadState state,
        int snapshotCount,
        String waitTarget,
        String topFrame) {

    public PersistentThread {
        Objects.requireNonNull(threadName, "threadName");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(waitTarget, "waitTarget");
        Objects.requireNonNull(topFrame, "topFrame");
        if (snapshotCount < 3) {
            throw new IllegalArgumentException("Persistent evidence needs at least three snapshots");
        }
    }
}
