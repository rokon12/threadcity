package ca.bazlur.threadcity.analysis;

import ca.bazlur.threadcity.domain.AnalysisResult;
import ca.bazlur.threadcity.domain.BlockingImpact;
import ca.bazlur.threadcity.domain.DeadlockCycle;
import ca.bazlur.threadcity.domain.Finding;
import ca.bazlur.threadcity.domain.JavaThread;
import ca.bazlur.threadcity.domain.LockReference;
import ca.bazlur.threadcity.domain.SynchronizerInsight;
import ca.bazlur.threadcity.domain.StackCluster;
import ca.bazlur.threadcity.domain.StackCohort;
import ca.bazlur.threadcity.domain.MethodHotspot;
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
import java.util.HexFormat;
import java.util.Set;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class ThreadDumpAnalyzer {

    private static final int MAX_IMPACT_EVIDENCE_THREADS = 12;

    private final IncidentPatternDetector patternDetector = new IncidentPatternDetector();

    public AnalysisResult analyze(ThreadSnapshot snapshot) {
        List<WaitEdge> waitEdges = buildWaitEdges(snapshot);
        List<DeadlockCycle> deadlocks = findDeadlocks(snapshot.threads(), waitEdges);
        List<BlockingImpact> blockingImpacts = calculateBlockingImpacts(waitEdges, deadlocks);
        List<SynchronizerInsight> synchronizers = analyzeSynchronizers(snapshot, deadlocks, blockingImpacts);
        List<StackCohort> stackCohorts = findStackCohorts(snapshot.threads());
        List<MethodHotspot> methodHotspots = findMethodHotspots(snapshot.threads());
        List<StackCluster> stackClusters = stackCohorts.stream()
                .filter(StackCohort::repeated)
                .map(cohort -> new StackCluster(cohort.stackFrames(), cohort.threads()))
                .toList();
        var patterns = patternDetector.detect(
                deadlocks, blockingImpacts, synchronizers, stackCohorts, methodHotspots);
        List<Finding> findings = createFindings(snapshot, deadlocks, stackClusters);
        return new AnalysisResult(
                snapshot,
                waitEdges,
                deadlocks,
                blockingImpacts,
                synchronizers,
                stackCohorts,
                methodHotspots,
                stackClusters,
                patterns,
                findings);
    }

    private List<SynchronizerInsight> analyzeSynchronizers(
            ThreadSnapshot snapshot,
            List<DeadlockCycle> deadlocks,
            List<BlockingImpact> impacts) {
        Map<String, SynchronizerBuilder> synchronizers = new LinkedHashMap<>();
        snapshot.threads().forEach(thread -> {
            thread.ownedLocks().forEach(lock -> synchronizers
                    .computeIfAbsent(lock.id(), ignored -> new SynchronizerBuilder(lock))
                    .addOwner(thread));
            if (thread.waitingOn() != null) {
                SynchronizerBuilder builder = synchronizers.computeIfAbsent(
                        thread.waitingOn().id(), ignored -> new SynchronizerBuilder(thread.waitingOn()));
                if (thread.waitKind().canFormOwnershipEdge()) {
                    builder.addAcquisitionWaiter(thread);
                } else {
                    builder.addNotificationWaiter(thread);
                }
            }
        });

        Set<String> deadlockedLocks = deadlocks.stream()
                .flatMap(cycle -> cycle.edges().stream())
                .map(edge -> edge.lock().id())
                .collect(Collectors.toUnmodifiableSet());
        Map<Integer, Integer> impactByOwner = impacts.stream().collect(Collectors.toMap(
                impact -> impact.blocker().id(),
                BlockingImpact::transitivelyBlocked));

        return synchronizers.values().stream()
                .map(builder -> builder.build(deadlockedLocks, impactByOwner))
                .sorted(Comparator.comparingInt((SynchronizerInsight insight) -> insight.risk().severity())
                        .reversed()
                        .thenComparing(Comparator.comparingInt(SynchronizerInsight::waiterCount).reversed())
                        .thenComparing(insight -> insight.lock().className())
                        .thenComparing(insight -> insight.lock().id()))
                .toList();
    }

    private List<BlockingImpact> calculateBlockingImpacts(
            List<WaitEdge> edges,
            List<DeadlockCycle> deadlocks) {
        Map<Integer, JavaThread> owners = new LinkedHashMap<>();
        Map<Integer, List<JavaThread>> waitersByOwner = new LinkedHashMap<>();
        Map<Integer, Integer> ownerByWaiter = new LinkedHashMap<>();
        Map<Integer, JavaThread> threadsById = new LinkedHashMap<>();
        edges.forEach(edge -> {
            owners.putIfAbsent(edge.owner().id(), edge.owner());
            threadsById.putIfAbsent(edge.owner().id(), edge.owner());
            threadsById.putIfAbsent(edge.waiter().id(), edge.waiter());
            ownerByWaiter.put(edge.waiter().id(), edge.owner().id());
            waitersByOwner.computeIfAbsent(edge.owner().id(), ignored -> new ArrayList<>())
                    .add(edge.waiter());
        });

        Set<Integer> cycleThreadIds = deadlocks.stream()
                .flatMap(cycle -> cycle.threads().stream())
                .map(JavaThread::id)
                .collect(Collectors.toUnmodifiableSet());
        Map<Integer, Integer> descendantCounts = new LinkedHashMap<>();
        Map<Integer, Integer> maximumDepths = new LinkedHashMap<>();
        Map<Integer, Integer> remainingChildren = new LinkedHashMap<>();
        ArrayDeque<Integer> leaves = new ArrayDeque<>();

        threadsById.keySet().stream()
                .filter(id -> !cycleThreadIds.contains(id))
                .forEach(id -> {
                    descendantCounts.put(id, 0);
                    maximumDepths.put(id, 0);
                    int children = (int) waitersByOwner.getOrDefault(id, List.of()).stream()
                            .filter(waiter -> !cycleThreadIds.contains(waiter.id()))
                            .count();
                    remainingChildren.put(id, children);
                    if (children == 0) {
                        leaves.addLast(id);
                    }
                });

        while (!leaves.isEmpty()) {
            int child = leaves.removeFirst();
            Integer parent = ownerByWaiter.get(child);
            if (parent == null || cycleThreadIds.contains(parent)) {
                continue;
            }
            descendantCounts.merge(parent, 1 + descendantCounts.get(child), Integer::sum);
            maximumDepths.merge(parent, 1 + maximumDepths.get(child), Math::max);
            int unprocessed = remainingChildren.merge(parent, -1, Integer::sum);
            if (unprocessed == 0) {
                leaves.addLast(parent);
            }
        }

        deadlocks.forEach(cycle -> addCycleImpactStatistics(
                cycle, waitersByOwner, descendantCounts, maximumDepths));

        return owners.values().stream()
                .map(owner -> new BlockingImpact(
                        owner,
                        waitersByOwner.getOrDefault(owner.id(), List.of()).size(),
                        descendantCounts.getOrDefault(owner.id(), 0),
                        maximumDepths.getOrDefault(owner.id(), 0),
                        affectedThreadSample(owner, waitersByOwner)))
                .sorted(Comparator.comparingInt(BlockingImpact::transitivelyBlocked)
                        .reversed()
                        .thenComparing(Comparator.comparingInt(BlockingImpact::maximumDepth).reversed())
                        .thenComparing(impact -> impact.blocker().name()))
                .toList();
    }

    private void addCycleImpactStatistics(
            DeadlockCycle cycle,
            Map<Integer, List<JavaThread>> waitersByOwner,
            Map<Integer, Integer> descendantCounts,
            Map<Integer, Integer> maximumDepths) {
        Map<Integer, JavaThread> cycleThreads = cycle.threads().stream()
                .collect(Collectors.toMap(JavaThread::id, Function.identity()));
        Map<Integer, Integer> cycleChildByOwner = cycle.edges().stream()
                .collect(Collectors.toMap(edge -> edge.owner().id(), edge -> edge.waiter().id()));
        List<Integer> ordered = new ArrayList<>(cycleThreads.size());
        int current = cycle.threads().getFirst().id();
        do {
            ordered.add(current);
            current = cycleChildByOwner.get(current);
        } while (current != ordered.getFirst());

        int totalAttached = 0;
        int[] branchDepths = new int[ordered.size()];
        for (int index = 0; index < ordered.size(); index++) {
            int cycleThreadId = ordered.get(index);
            for (JavaThread child : waitersByOwner.getOrDefault(cycleThreadId, List.of())) {
                if (cycleThreads.containsKey(child.id())) {
                    continue;
                }
                totalAttached += 1 + descendantCounts.getOrDefault(child.id(), 0);
                branchDepths[index] = Math.max(
                        branchDepths[index], 1 + maximumDepths.getOrDefault(child.id(), 0));
            }
        }

        int transitiveCount = ordered.size() - 1 + totalAttached;
        int[] cycleDepths = circularMaximumDepths(branchDepths);
        for (int index = 0; index < ordered.size(); index++) {
            descendantCounts.put(ordered.get(index), transitiveCount);
            maximumDepths.put(ordered.get(index), cycleDepths[index]);
        }
    }

    private int[] circularMaximumDepths(int[] branchDepths) {
        int size = branchDepths.length;
        int[] depths = new int[size];
        ArrayDeque<Integer> maximums = new ArrayDeque<>();
        for (int index = 0; index < size * 2; index++) {
            int value = index + branchDepths[index % size];
            while (!maximums.isEmpty()
                    && maximums.getLast() + branchDepths[maximums.getLast() % size] <= value) {
                maximums.removeLast();
            }
            maximums.addLast(index);
            int windowStart = index - size + 1;
            while (!maximums.isEmpty() && maximums.getFirst() < windowStart) {
                maximums.removeFirst();
            }
            if (windowStart >= 0 && windowStart < size) {
                int maximumIndex = maximums.getFirst();
                depths[windowStart] = maximumIndex
                        + branchDepths[maximumIndex % size]
                        - windowStart;
            }
        }
        return depths;
    }

    private List<JavaThread> affectedThreadSample(
            JavaThread blocker,
            Map<Integer, List<JavaThread>> waitersByOwner) {
        List<JavaThread> affected = new ArrayList<>(MAX_IMPACT_EVIDENCE_THREADS);
        Set<Integer> visited = new HashSet<>();
        visited.add(blocker.id());
        ArrayDeque<JavaThread> work = new ArrayDeque<>(waitersByOwner.getOrDefault(blocker.id(), List.of()));

        while (!work.isEmpty() && affected.size() < MAX_IMPACT_EVIDENCE_THREADS) {
            JavaThread current = work.removeFirst();
            if (!visited.add(current.id())) {
                continue;
            }
            affected.add(current);
            waitersByOwner.getOrDefault(current.id(), List.of()).forEach(work::addLast);
        }
        return List.copyOf(affected);
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

    private List<StackCohort> findStackCohorts(List<JavaThread> threads) {
        return threads.stream()
                .filter(thread -> !thread.stackFrames().isEmpty())
                .collect(Collectors.groupingBy(
                        thread -> String.join("\n", thread.stackFrames()),
                        LinkedHashMap::new,
                        Collectors.toList()))
                .values().stream()
                .sorted(Comparator.comparingInt((List<JavaThread> cluster) -> cluster.size())
                        .reversed()
                        .thenComparingInt(cluster -> cluster.getFirst().id()))
                .map(cluster -> new StackCohort(
                        fingerprint(cluster.getFirst().stackFrames()),
                        cluster.getFirst().stackFrames(),
                        cluster))
                .toList();
    }

    private List<MethodHotspot> findMethodHotspots(List<JavaThread> threads) {
        return threads.stream()
                .filter(thread -> thread.state() == ThreadState.RUNNABLE)
                .filter(thread -> !thread.stackFrames().isEmpty())
                .collect(Collectors.groupingBy(
                        JavaThread::topFrame,
                        LinkedHashMap::new,
                        Collectors.toList()))
                .entrySet().stream()
                .map(entry -> new MethodHotspot(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparingInt((MethodHotspot hotspot) -> hotspot.threads().size())
                        .reversed()
                        .thenComparing(MethodHotspot::method))
                .toList();
    }

    private String fingerprint(List<String> frames) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(String.join("\n", frames).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 6);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
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

    private static final class SynchronizerBuilder {
        private final LockReference lock;
        private final Map<Integer, JavaThread> owners = new LinkedHashMap<>();
        private final Map<Integer, JavaThread> acquisitionWaiters = new LinkedHashMap<>();
        private final Map<Integer, JavaThread> notificationWaiters = new LinkedHashMap<>();

        private SynchronizerBuilder(LockReference lock) {
            this.lock = lock;
        }

        private void addOwner(JavaThread thread) {
            owners.putIfAbsent(thread.id(), thread);
        }

        private void addAcquisitionWaiter(JavaThread thread) {
            acquisitionWaiters.putIfAbsent(thread.id(), thread);
        }

        private void addNotificationWaiter(JavaThread thread) {
            notificationWaiters.putIfAbsent(thread.id(), thread);
        }

        private SynchronizerInsight build(Set<String> deadlockedLocks, Map<Integer, Integer> impactByOwner) {
            SynchronizerInsight.Risk risk;
            if (deadlockedLocks.contains(lock.id())) {
                risk = SynchronizerInsight.Risk.DEADLOCKED;
            } else if (!acquisitionWaiters.isEmpty() && owners.size() == 1) {
                risk = SynchronizerInsight.Risk.CONTENDED;
            } else if (!acquisitionWaiters.isEmpty()) {
                risk = SynchronizerInsight.Risk.UNRESOLVED;
            } else if (!notificationWaiters.isEmpty()) {
                risk = SynchronizerInsight.Risk.NOTIFICATION;
            } else {
                risk = SynchronizerInsight.Risk.HELD;
            }
            int downstreamImpact = owners.values().stream()
                    .mapToInt(owner -> impactByOwner.getOrDefault(owner.id(), 0))
                    .max()
                    .orElse(0);
            return new SynchronizerInsight(
                    lock,
                    List.copyOf(owners.values()),
                    List.copyOf(acquisitionWaiters.values()),
                    List.copyOf(notificationWaiters.values()),
                    risk,
                    downstreamImpact);
        }
    }
}
