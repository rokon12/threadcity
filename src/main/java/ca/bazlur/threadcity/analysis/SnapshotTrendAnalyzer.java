package ca.bazlur.threadcity.analysis;

import ca.bazlur.threadcity.domain.AnalysisResult;
import ca.bazlur.threadcity.domain.JavaThread;
import ca.bazlur.threadcity.domain.PersistentThread;
import ca.bazlur.threadcity.domain.ThreadState;
import ca.bazlur.threadcity.domain.ThreadIdentities;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Finds repeat observations that a single thread dump cannot establish.
 */
public final class SnapshotTrendAnalyzer {

    public List<PersistentThread> findPersistentStalls(List<AnalysisResult> snapshots) {
        if (snapshots.size() < 3) {
            return List.of();
        }
        List<Map<String, JavaThread>> indexedSnapshots = snapshots.stream()
                .map(snapshot -> ThreadIdentities.index(snapshot.snapshot().threads()))
                .toList();
        Map<String, JavaThread> first = indexedSnapshots.getFirst();
        return first.entrySet().stream()
                .filter(entry -> isPotentialStall(entry.getValue()))
                .filter(entry -> indexedSnapshots.stream()
                        .map(threads -> threads.get(entry.getKey()))
                        .allMatch(thread -> sameExecutionPoint(entry.getValue(), thread)))
                .map(entry -> persistent(entry.getValue(), snapshots.size()))
                .toList();
    }

    private boolean isPotentialStall(JavaThread thread) {
        if (thread.state() == ThreadState.BLOCKED) {
            return true;
        }
        String stack = String.join("\n", thread.stackFrames()).toLowerCase(Locale.ROOT);
        return (thread.state() == ThreadState.WAITING || thread.state() == ThreadState.TIMED_WAITING)
                && (stack.contains("getconnection")
                || stack.contains("connectionpool.borrow")
                || stack.contains("futuretask.get")
                || stack.contains("completablefuture.join")
                || stack.contains("socket")
                || stack.contains("filedispatcher"));
    }

    private boolean sameExecutionPoint(JavaThread expected, JavaThread observed) {
        if (observed == null || expected.state() != observed.state()) {
            return false;
        }
        String expectedWait = expected.waitingOn() == null ? "none" : expected.waitingOn().id();
        String observedWait = observed.waitingOn() == null ? "none" : observed.waitingOn().id();
        return expectedWait.equals(observedWait) && expected.stackFrames().equals(observed.stackFrames());
    }

    private PersistentThread persistent(JavaThread thread, int snapshotCount) {
        String wait = thread.waitingOn() == null
                ? "No parsed lock target"
                : thread.waitKind().description() + " " + thread.waitingOn().shortId();
        return new PersistentThread(thread.name(), thread.state(), snapshotCount, wait, thread.topFrame());
    }
}
