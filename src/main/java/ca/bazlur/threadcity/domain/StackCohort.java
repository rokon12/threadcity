package ca.bazlur.threadcity.domain;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Threads sharing the exact same ordered Java stack frames.
 */
public record StackCohort(
        String fingerprint,
        List<String> stackFrames,
        List<JavaThread> threads) {

    public StackCohort {
        Objects.requireNonNull(fingerprint, "fingerprint");
        stackFrames = List.copyOf(stackFrames);
        threads = List.copyOf(threads);
        if (threads.isEmpty()) {
            throw new IllegalArgumentException("A stack cohort needs at least one thread");
        }
    }

    public String topFrame() {
        return stackFrames.isEmpty() ? "No Java stack frame available" : stackFrames.getFirst();
    }

    public boolean repeated() {
        return threads.size() > 1;
    }

    public Map<ThreadState, Long> stateCounts() {
        Map<ThreadState, Long> counts = new EnumMap<>(ThreadState.class);
        threads.forEach(thread -> counts.merge(thread.state(), 1L, Long::sum));
        return Map.copyOf(counts);
    }
}
