package ca.bazlur.threadcity.domain;

import java.util.Objects;

public record WaitEdge(JavaThread waiter, JavaThread owner, LockReference lock) {

    public WaitEdge {
        Objects.requireNonNull(waiter, "waiter");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(lock, "lock");
    }
}
