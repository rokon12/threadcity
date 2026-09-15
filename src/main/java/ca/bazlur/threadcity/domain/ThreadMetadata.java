package ca.bazlur.threadcity.domain;

import java.util.Locale;
import java.util.Objects;

/**
 * Optional JVM telemetry parsed from a thread header.
 */
public record ThreadMetadata(
        Long javaThreadNumber,
        Integer priority,
        Integer osPriority,
        Double cpuMillis,
        Double elapsedSeconds,
        String tid,
        String nid,
        boolean daemon,
        ThreadKind kind,
        String group) {

    public ThreadMetadata {
        if (javaThreadNumber != null && javaThreadNumber < 0) {
            throw new IllegalArgumentException("Java thread number cannot be negative");
        }
        if (cpuMillis != null && cpuMillis < 0) {
            throw new IllegalArgumentException("CPU time cannot be negative");
        }
        if (elapsedSeconds != null && elapsedSeconds < 0) {
            throw new IllegalArgumentException("Elapsed time cannot be negative");
        }
        tid = normalize(tid);
        nid = normalize(nid);
        group = normalize(group);
        kind = Objects.requireNonNullElse(kind, ThreadKind.UNKNOWN);
    }

    public static ThreadMetadata empty() {
        return new ThreadMetadata(null, null, null, null, null, null, null, false, ThreadKind.UNKNOWN, null);
    }

    public String cpuDisplay() {
        return cpuMillis == null ? "—" : String.format(Locale.ROOT, "%.2f ms", cpuMillis);
    }

    public String elapsedDisplay() {
        return elapsedSeconds == null ? "—" : String.format(Locale.ROOT, "%.2f s", elapsedSeconds);
    }

    public String searchText() {
        return String.join(" ",
                javaThreadNumber == null ? "" : javaThreadNumber.toString(),
                priority == null ? "" : priority.toString(),
                osPriority == null ? "" : osPriority.toString(),
                tid == null ? "" : tid,
                nid == null ? "" : nid,
                group == null ? "" : group,
                kind.name(),
                daemon ? "daemon" : "non-daemon").toLowerCase(Locale.ROOT);
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    public enum ThreadKind {
        PLATFORM("Platform"),
        VIRTUAL("Virtual"),
        UNKNOWN("Unspecified");

        private final String label;

        ThreadKind(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }
}
