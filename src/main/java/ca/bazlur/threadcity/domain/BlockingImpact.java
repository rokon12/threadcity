package ca.bazlur.threadcity.domain;

import java.util.List;
import java.util.Objects;

/**
 * Quantifies how much traffic depends directly or transitively on one thread.
 */
public record BlockingImpact(
        JavaThread blocker,
        int directlyBlocked,
        int transitivelyBlocked,
        int maximumDepth,
        List<JavaThread> affectedThreads) {

    public BlockingImpact {
        Objects.requireNonNull(blocker, "blocker");
        affectedThreads = List.copyOf(affectedThreads);
        if (directlyBlocked < 0 || transitivelyBlocked < directlyBlocked || maximumDepth < 0) {
            throw new IllegalArgumentException("Invalid blocking impact counts");
        }
    }
}
