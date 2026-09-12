package ca.bazlur.threadcity.domain;

public enum JfrEventCategory {
    MONITOR("Monitor contention"),
    PARK("Thread parking"),
    CPU("CPU samples"),
    IO("I/O stalls"),
    GC("GC pauses"),
    VIRTUAL_THREAD("Virtual threads"),
    LAB("Lab signals");

    private final String label;

    JfrEventCategory(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
