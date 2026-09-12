package ca.bazlur.threadcity.analysis;

import ca.bazlur.threadcity.domain.AnalysisResult;
import ca.bazlur.threadcity.domain.BlockingImpact;
import ca.bazlur.threadcity.domain.DeadlockCycle;
import ca.bazlur.threadcity.domain.Finding;
import ca.bazlur.threadcity.domain.JavaThread;
import ca.bazlur.threadcity.domain.LockReference;
import ca.bazlur.threadcity.domain.StackCluster;
import ca.bazlur.threadcity.domain.ThreadSnapshot;
import ca.bazlur.threadcity.domain.ThreadState;
import ca.bazlur.threadcity.domain.WaitEdge;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class ThreadDumpAnalyzer {

    public AnalysisResult analyze(ThreadSnapshot snapshot) {
        List<WaitEdge> waitEdges = buildWaitEdges(snapshot);
        List<DeadlockCycle> deadlocks = findDeadlocks(snapshot.threads(), waitEdges);
        List<BlockingImpact> blockingImpacts = calculateBlockingImpacts(waitEdges);
        List<StackCluster> stackClusters = findStackClusters(snapshot.threads());
        List<Finding> findings = createFindings(snapshot, deadlocks, stackClusters);
        return new AnalysisResult(snapshot, waitEdges, deadlocks, blockingImpacts, stackClusters, findings);
    }

    private List<BlockingImpact> calculateBlockingImpacts(List<WaitEdge> edges) {
        Map<Integer, JavaThread> owners = new LinkedHashMap<>();
        Map<Integer, List<JavaThread>> waitersByOwner = new LinkedHashMap<>();
        edges.forEach(edge -> {
            owners.putIfAbsent(edge.owner().id(), edge.owner());
            waitersByOwner.computeIfAbsent(edge.owner().id(), ignored -> new ArrayList<>())
                    .add(edge.waiter());
        });

        return owners.values().stream()
                .map(owner -> blockingImpact(owner, waitersByOwner))
                .sorted(Comparator.comparingInt(BlockingImpact::transitivelyBlocked)
                        .reversed()
                        .thenComparing(Comparator.comparingInt(BlockingImpact::maximumDepth).reversed())
                        .thenComparing(impact -> impact.blocker().name()))
                .toList();
    }

    private BlockingImpact blockingImpact(
            JavaThread blocker,
            Map<Integer, List<JavaThread>> waitersByOwner) {
        List<JavaThread> directWaiters = waitersByOwner.getOrDefault(blocker.id(), List.of());
        List<JavaThread> affected = new ArrayList<>();
        Set<Integer> visited = new HashSet<>();
        visited.add(blocker.id());
        ArrayDeque<ThreadAtDepth> work = new ArrayDeque<>();
        directWaiters.forEach(waiter -> work.addLast(new ThreadAtDepth(waiter, 1)));
        int maximumDepth = 0;

        while (!work.isEmpty()) {
            ThreadAtDepth current = work.removeFirst();
            if (!visited.add(current.thread().id())) {
                continue;
            }
            affected.add(current.thread());
            maximumDepth = Math.max(maximumDepth, current.depth());
            waitersByOwner.getOrDefault(current.thread().id(), List.of())
                    .forEach(waiter -> work.addLast(new ThreadAtDepth(waiter, current.depth() + 1)));
        }

        return new BlockingImpact(
                blocker,
                directWaiters.size(),
                affected.size(),
                maximumDepth,
                affected);
    }

    private List<WaitEdge> buildWaitEdges(ThreadSnapshot snapshot) {
        Map<String, List<JavaThread>> ownersByLock = new LinkedHashMap<>();
        snapshot.threads().forEach(thread -> thread.ownedLocks()
                .forEach(lock -> ownersByLock.computeIfAbsent(lock.id(), ignored -> new ArrayList<>()).add(thread)));

        return snapshot.threads().stream()
                .filter(JavaThread::isWaitingForOwnedLock)
                .map(waiter -> {
                    List<JavaThread> owners = ownersByLock.getOrDefault(waiter.waitingOn().id(), List.of());
                    if (owners.size() != 1 || owners.getFirst().id() == waiter.id()) {
                        return null;
                    }
                    return new WaitEdge(waiter, owners.getFirst(), waiter.waitingOn());
                })
                .filter(edge -> edge != null)
                .toList();
    }

    private List<DeadlockCycle> findDeadlocks(List<JavaThread> threads, List<WaitEdge> edges) {
        Map<Integer, WaitEdge> outgoing = edges.stream()
                .collect(Collectors.toMap(edge -> edge.waiter().id(), Function.identity(), (first, ignored) -> first));
        Set<Integer> globallyVisited = new HashSet<>();
        List<DeadlockCycle> cycles = new ArrayList<>();

        for (JavaThread start : threads) {
            if (globallyVisited.contains(start.id())) {
                continue;
            }

            Map<Integer, Integer> positionInPath = new LinkedHashMap<>();
            List<JavaThread> path = new ArrayList<>();
            JavaThread current = start;

            while (current != null) {
                Integer cycleStart = positionInPath.get(current.id());
                if (cycleStart != null) {
                    List<WaitEdge> cycleEdges = path.subList(cycleStart, path.size()).stream()
                            .map(thread -> outgoing.get(thread.id()))
                            .toList();
                    cycles.add(new DeadlockCycle(cycleEdges));
                    break;
                }
                if (globallyVisited.contains(current.id())) {
                    break;
                }

                positionInPath.put(current.id(), path.size());
                path.add(current);
                WaitEdge next = outgoing.get(current.id());
                current = next == null ? null : next.owner();
            }
            path.forEach(thread -> globallyVisited.add(thread.id()));
        }
        return cycles;
    }

    private List<StackCluster> findStackClusters(List<JavaThread> threads) {
        return threads.stream()
                .filter(thread -> !thread.stackFrames().isEmpty())
                .collect(Collectors.groupingBy(
                        thread -> String.join("\n", thread.stackFrames()),
                        LinkedHashMap::new,
                        Collectors.toList()))
                .values().stream()
                .filter(cluster -> cluster.size() > 1)
                .sorted(Comparator.comparingInt((List<JavaThread> cluster) -> cluster.size())
                        .reversed()
                        .thenComparingInt(cluster -> cluster.getFirst().id()))
                .map(cluster -> new StackCluster(cluster.getFirst().stackFrames(), cluster))
                .toList();
    }

    private List<Finding> createFindings(
            ThreadSnapshot snapshot,
            List<DeadlockCycle> deadlocks,
            List<StackCluster> stackClusters) {
        List<Finding> findings = new ArrayList<>();

        deadlocks.forEach(cycle -> findings.add(new Finding(
                Finding.Severity.CRITICAL,
                "Circular lock dependency detected",
                "Each thread in this cycle is waiting for a lock owned by the next thread. None can make progress.",
                cycle.threads().stream().map(JavaThread::name).toList())));

        long blocked = snapshot.threads().stream().filter(thread -> thread.state() == ThreadState.BLOCKED).count();
        if (blocked > 0 && deadlocks.isEmpty()) {
            findings.add(new Finding(
                    Finding.Severity.WARNING,
                    blocked + " blocked thread" + (blocked == 1 ? "" : "s"),
                    "Blocked threads are waiting to enter a monitor. Inspect their lock owners and shared stack frames.",
                    snapshot.threads().stream()
                            .filter(thread -> thread.state() == ThreadState.BLOCKED)
                            .map(JavaThread::name)
                            .toList()));
        }

        stackClusters.stream()
                .filter(cluster -> cluster.threads().size() >= 3)
                .findFirst()
                .ifPresent(cluster -> findings.add(new Finding(
                        Finding.Severity.INFO,
                        cluster.threads().size() + " threads share the same stack",
                        "Repeated stacks can reveal a contention point, an intentionally idle worker pool, or duplicated work.",
                        cluster.threads().stream().map(JavaThread::name).toList())));

        if (findings.isEmpty()) {
            findings.add(new Finding(
                    Finding.Severity.INFO,
                    "No confirmed deadlock found",
                    "ThreadCity found no circular lock dependency in this snapshot.",
                    List.of()));
        }
        return findings;
    }

    private record ThreadAtDepth(JavaThread thread, int depth) {
    }
}
