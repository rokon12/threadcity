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

    private final IncidentPatternDetector patternDetector = new IncidentPatternDetector();

    public AnalysisResult analyze(ThreadSnapshot snapshot) {
        List<WaitEdge> waitEdges = buildWaitEdges(snapshot);
        List<DeadlockCycle> deadlocks = findDeadlocks(snapshot.threads(), waitEdges);
        List<BlockingImpact> blockingImpacts = calculateBlockingImpacts(waitEdges);
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

    private record ThreadAtDepth(JavaThread thread, int depth) {
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
