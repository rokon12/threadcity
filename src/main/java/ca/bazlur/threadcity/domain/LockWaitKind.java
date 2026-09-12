package ca.bazlur.threadcity.domain;

public enum LockWaitKind {
    MONITOR_ENTRY("waiting to enter a monitor", true),
    OBJECT_WAIT("waiting for a notification", false),
    PARKING("parked for a synchronizer", true),
    UNKNOWN("waiting", false);

    private final String description;
    private final boolean ownershipDependent;

    LockWaitKind(String description, boolean ownershipDependent) {
        this.description = description;
        this.ownershipDependent = ownershipDependent;
    }

    public String description() {
        return description;
    }

    public boolean canFormOwnershipEdge() {
        return ownershipDependent;
    }
}
