package ca.bazlur.threadcity.analysis;

import ca.bazlur.threadcity.domain.AnalysisResult;
import ca.bazlur.threadcity.domain.DeadlockCycle;
import ca.bazlur.threadcity.domain.JavaThread;
import ca.bazlur.threadcity.domain.SnapshotDiff;
import ca.bazlur.threadcity.domain.ThreadChange;
import ca.bazlur.threadcity.domain.WaitEdge;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
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
        Map<String, JavaThread> beforeThreads = indexThreads(before.snapshot().threads());
        Map<String, JavaThread> afterThreads = indexThreads(after.snapshot().threads());
        Set<String> identities = new LinkedHashSet<>(beforeThreads.keySet());
        identities.addAll(afterThreads.keySet());

        List<ThreadChange> changes = identities.stream()
                .map(identity -> compareThread(identity, beforeThreads.get(identity), afterThreads.get(identity)))
                .sorted(Comparator.comparing(ThreadChange::changed).reversed()
                        .thenComparing(ThreadChange::threadName)
                        .thenComparing(ThreadChange::identity))
                .toList();

        Set<String> beforeEdges = before.waitEdges().stream()
                .map(this::edgeSignature)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> afterEdges = after.waitEdges().stream()
                .map(this::edgeSignature)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> beforeCycles = before.deadlocks().stream()
                .map(this::cycleSignature)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> afterCycles = after.deadlocks().stream()
                .map(this::cycleSignature)
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

    private Map<String, JavaThread> indexThreads(List<JavaThread> threads) {
        Map<String, Integer> totals = new LinkedHashMap<>();
        threads.forEach(thread -> totals.merge(thread.name(), 1, Integer::sum));
        Map<String, Integer> occurrences = new LinkedHashMap<>();
        Map<String, JavaThread> indexed = new LinkedHashMap<>();
        threads.forEach(thread -> {
            int occurrence = occurrences.merge(thread.name(), 1, Integer::sum);
            String identity = totals.get(thread.name()) == 1
                    ? thread.name()
                    : thread.name() + " [" + occurrence + "]";
            indexed.put(identity, thread);
        });
        return indexed;
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
        return new ThreadChange(identity, before, after, kinds);
    }

    private String waitSignature(JavaThread thread) {
        if (thread.waitingOn() == null) {
            return "none";
        }
        return thread.waitKind() + "@" + thread.waitingOn().id();
    }

    private String edgeSignature(WaitEdge edge) {
        return edge.waiter().name() + "->" + edge.owner().name() + "@" + edge.lock().id();
    }

    private String cycleSignature(DeadlockCycle cycle) {
        return cycle.threads().stream().map(JavaThread::name).sorted().collect(Collectors.joining("|"));
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
