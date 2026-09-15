package ca.bazlur.threadcity.analysis;

import ca.bazlur.threadcity.domain.AnalysisResult;
import ca.bazlur.threadcity.domain.DeadlockCycle;
import ca.bazlur.threadcity.domain.JavaThread;
import ca.bazlur.threadcity.domain.SnapshotDiff;
import ca.bazlur.threadcity.domain.ThreadChange;
import ca.bazlur.threadcity.domain.ThreadIdentities;
import ca.bazlur.threadcity.domain.WaitEdge;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Aligns thread-name occurrences and compares adjacent JVM snapshots.
 */
public final class SnapshotDiffAnalyzer {

    public SnapshotDiff compare(AnalysisResult before, AnalysisResult after) {
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");
        Map<String, JavaThread> beforeThreads = ThreadIdentities.index(before.snapshot().threads());
        Map<String, JavaThread> afterThreads = ThreadIdentities.index(after.snapshot().threads());
        Set<String> identities = new LinkedHashSet<>(beforeThreads.keySet());
        identities.addAll(afterThreads.keySet());

        List<ThreadChange> changes = identities.stream()
                .map(identity -> compareThread(identity, beforeThreads.get(identity), afterThreads.get(identity)))
                .sorted(Comparator.comparing(ThreadChange::changed).reversed()
                        .thenComparing(ThreadChange::threadName)
                        .thenComparing(ThreadChange::identity))
                .toList();

        Map<Integer, String> beforeIdentities = ThreadIdentities.keysByInternalId(before.snapshot().threads());
        Map<Integer, String> afterIdentities = ThreadIdentities.keysByInternalId(after.snapshot().threads());
        Set<String> beforeEdges = before.waitEdges().stream()
                .map(edge -> edgeSignature(edge, beforeIdentities))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> afterEdges = after.waitEdges().stream()
                .map(edge -> edgeSignature(edge, afterIdentities))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> beforeCycles = before.deadlocks().stream()
                .map(cycle -> cycleSignature(cycle, beforeIdentities))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> afterCycles = after.deadlocks().stream()
                .map(cycle -> cycleSignature(cycle, afterIdentities))
                .collect(Collectors.toCollection(LinkedHashSet::new));

        return new SnapshotDiff(
                before,
                after,
                changes,
                differenceSize(afterEdges, beforeEdges),
                differenceSize(beforeEdges, afterEdges),
                intersectionSize(beforeEdges, afterEdges),
                differenceSize(afterCycles, beforeCycles),
                differenceSize(beforeCycles, afterCycles));
    }

    private ThreadChange compareThread(String identity, JavaThread before, JavaThread after) {
        EnumSet<ThreadChange.Kind> kinds = EnumSet.noneOf(ThreadChange.Kind.class);
        if (before == null) {
            kinds.add(ThreadChange.Kind.ADDED);
        } else if (after == null) {
            kinds.add(ThreadChange.Kind.REMOVED);
        } else {
            if (before.state() != after.state()) {
                kinds.add(ThreadChange.Kind.STATE_CHANGED);
            }
            if (!waitSignature(before).equals(waitSignature(after))) {
                kinds.add(ThreadChange.Kind.WAIT_CHANGED);
            }
            if (!before.stackFrames().equals(after.stackFrames())) {
                kinds.add(ThreadChange.Kind.STACK_CHANGED);
            }
            if (kinds.isEmpty()) {
                kinds.add(ThreadChange.Kind.UNCHANGED);
            }
        }
        JavaThread displayThread = after == null ? before : after;
        return new ThreadChange(ThreadIdentities.displayKey(identity, displayThread), before, after, kinds);
    }

    private String waitSignature(JavaThread thread) {
        if (thread.waitingOn() == null) {
            return "none";
        }
        return thread.waitKind() + "@" + thread.waitingOn().id();
    }

    private String edgeSignature(WaitEdge edge, Map<Integer, String> identities) {
        return identities.get(edge.waiter().id()) + "->" + identities.get(edge.owner().id())
                + "@" + edge.lock().id();
    }

    private String cycleSignature(DeadlockCycle cycle, Map<Integer, String> identities) {
        return cycle.threads().stream()
                .map(thread -> identities.get(thread.id()))
                .sorted()
                .collect(Collectors.joining("|"));
    }

    private int differenceSize(Set<String> left, Set<String> right) {
        List<String> difference = new ArrayList<>(left);
        difference.removeAll(right);
        return difference.size();
    }

    private int intersectionSize(Set<String> left, Set<String> right) {
        List<String> intersection = new ArrayList<>(left);
        intersection.retainAll(right);
        return intersection.size();
    }
}
