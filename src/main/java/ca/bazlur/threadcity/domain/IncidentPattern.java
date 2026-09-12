package ca.bazlur.threadcity.domain;

import java.util.List;
import java.util.Objects;

/**
 * A bounded, explainable incident signature derived from observed dump evidence.
 */
public record IncidentPattern(
        Type type,
        Finding.Severity severity,
        Confidence confidence,
        String title,
        String explanation,
        List<String> evidence,
        List<String> threadNames) {

    public IncidentPattern {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(confidence, "confidence");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(explanation, "explanation");
        evidence = List.copyOf(evidence);
        threadNames = List.copyOf(threadNames);
    }

    public enum Type {
        DEADLOCK("Deadlock"),
        LOCK_CONVOY("Lock convoy"),
        BLOCKING_CASCADE("Blocking cascade"),
        EXECUTOR_STARVATION("Executor starvation"),
        CONNECTION_POOL_EXHAUSTION("Connection-pool exhaustion"),
        IO_STALL("I/O stall"),
        CPU_HOTSPOT("CPU hotspot");

        private final String label;

        Type(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public enum Confidence {
        CONFIRMED("Confirmed", 3),
        STRONG_SIGNAL("Strong signal", 2),
        SUSPECT("Suspect", 1);

        private final String label;
        private final int rank;

        Confidence(String label, int rank) {
            this.label = label;
            this.rank = rank;
        }

        public String label() {
            return label;
        }

        public int rank() {
            return rank;
        }
    }
}
