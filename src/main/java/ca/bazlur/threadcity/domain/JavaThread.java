package ca.bazlur.threadcity.domain;

import java.util.List;
import java.util.Objects;

public record JavaThread(
        int id,
        String name,
        String header,
        ThreadState state,
        List<String> stackFrames,
        List<LockReference> ownedLocks,
        LockReference waitingOn,
        LockWaitKind waitKind,
        ThreadMetadata metadata) {

    public JavaThread {
        Objects.requireNonNull(name, "name");
        header = header == null ? "" : header;
        state = state == null ? ThreadState.UNKNOWN : state;
        stackFrames = List.copyOf(stackFrames);
        ownedLocks = List.copyOf(ownedLocks);
        waitKind = waitKind == null ? LockWaitKind.UNKNOWN : waitKind;
        metadata = Objects.requireNonNullElseGet(metadata, ThreadMetadata::empty);
    }

    public String topFrame() {
        return stackFrames.isEmpty() ? "No Java stack frame available" : stackFrames.getFirst();
    }

    public boolean isWaitingForLock() {
        return waitingOn != null;
    }

    public boolean isWaitingForOwnedLock() {
        return waitingOn != null && waitKind.canFormOwnershipEdge();
    }
}
