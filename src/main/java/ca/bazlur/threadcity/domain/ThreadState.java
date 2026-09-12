package ca.bazlur.threadcity.domain;

public enum ThreadState {
    NEW,
    RUNNABLE,
    BLOCKED,
    WAITING,
    TIMED_WAITING,
    TERMINATED,
    UNKNOWN;

    public static ThreadState fromDump(String value) {
        if (value == null || value.isBlank()) {
            return UNKNOWN;
        }
        try {
            return valueOf(value.trim().replace('-', '_'));
        } catch (IllegalArgumentException ignored) {
            return UNKNOWN;
        }
    }
}
