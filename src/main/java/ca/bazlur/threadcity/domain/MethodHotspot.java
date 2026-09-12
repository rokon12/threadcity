package ca.bazlur.threadcity.domain;

import java.util.List;
import java.util.Objects;

/**
 * Runnable threads currently executing the same top Java frame.
 */
public record MethodHotspot(String method, List<JavaThread> threads) {

    public MethodHotspot {
        Objects.requireNonNull(method, "method");
        threads = List.copyOf(threads);
        if (threads.isEmpty()) {
            throw new IllegalArgumentException("A method hotspot needs at least one thread");
        }
    }
}
