package ca.bazlur.threadcity.domain;

import java.util.List;
import java.util.Objects;

/**
 * Cross-references one observed lock with its owners and waiters.
 */
public record SynchronizerInsight(
        LockReference lock,
        List<JavaThread> owners,
        List<JavaThread> acquisitionWaiters,
        List<JavaThread> notificationWaiters,
        Risk risk,
        int downstreamImpact) {

    public SynchronizerInsight {
        Objects.requireNonNull(lock, "lock");
        owners = List.copyOf(owners);
        acquisitionWaiters = List.copyOf(acquisitionWaiters);
        notificationWaiters = List.copyOf(notificationWaiters);
        Objects.requireNonNull(risk, "risk");
        if (downstreamImpact < 0) {
            throw new IllegalArgumentException("Downstream impact cannot be negative");
        }
    }

    public int waiterCount() {
        return acquisitionWaiters.size() + notificationWaiters.size();
    }

    public enum Risk {
        DEADLOCKED("Deadlocked", 5),
        CONTENDED("Contended", 4),
        UNRESOLVED("Owner unresolved", 3),
        NOTIFICATION("Notification wait", 2),
        HELD("Held", 1);

        private final String label;
        private final int severity;

        Risk(String label, int severity) {
            this.label = label;
            this.severity = severity;
        }

        public String label() {
            return label;
        }

        public int severity() {
            return severity;
        }
    }
}
