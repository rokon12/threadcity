package ca.bazlur.threadcity.analysis;

import ca.bazlur.threadcity.domain.BlockingImpact;
import ca.bazlur.threadcity.domain.DeadlockCycle;
import ca.bazlur.threadcity.domain.Finding;
import ca.bazlur.threadcity.domain.IncidentPattern;
import ca.bazlur.threadcity.domain.JavaThread;
import ca.bazlur.threadcity.domain.MethodHotspot;
import ca.bazlur.threadcity.domain.StackCohort;
import ca.bazlur.threadcity.domain.SynchronizerInsight;
import ca.bazlur.threadcity.domain.ThreadState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Recognizes common JVM failure shapes without turning a single snapshot into an unsupported claim.
 */
public final class IncidentPatternDetector {

    private static final int GROUP_THRESHOLD = 3;
    private static final int MAX_EVIDENCE_THREADS = 12;

    public List<IncidentPattern> detect(
            List<DeadlockCycle> deadlocks,
            List<BlockingImpact> impacts,
            List<SynchronizerInsight> synchronizers,
            List<StackCohort> cohorts,
            List<MethodHotspot> hotspots) {
        List<IncidentPattern> patterns = new ArrayList<>();
        deadlocks.forEach(cycle -> patterns.add(deadlock(cycle)));
        synchronizers.stream()
                .filter(insight -> insight.acquisitionWaiters().size() >= GROUP_THRESHOLD)
                .forEach(insight -> patterns.add(lockConvoy(insight)));
        impacts.stream()
                .filter(impact -> impact.transitivelyBlocked() >= GROUP_THRESHOLD)
                .filter(impact -> patterns.stream().noneMatch(pattern -> pattern.type() == IncidentPattern.Type.DEADLOCK
                        && pattern.threadNames().contains(impact.blocker().name())))
                .forEach(impact -> patterns.add(blockingCascade(impact)));
        cohorts.stream()
                .filter(cohort -> cohort.threads().size() >= GROUP_THRESHOLD)
                .forEach(cohort -> detectCohortPattern(cohort).ifPresent(patterns::add));
        hotspots.stream()
                .filter(hotspot -> hotspot.threads().size() >= 2)
                .filter(this::hasMaterialCpuEvidence)
                .forEach(hotspot -> patterns.add(cpuHotspot(hotspot)));
        return patterns.stream()
                .sorted(Comparator.comparingInt((IncidentPattern pattern) -> severityRank(pattern.severity()))
                        .reversed()
                        .thenComparing(Comparator.comparingInt(
                                (IncidentPattern pattern) -> pattern.confidence().rank()).reversed())
                        .thenComparing(IncidentPattern::title))
                .toList();
    }

    private IncidentPattern deadlock(DeadlockCycle cycle) {
        return new IncidentPattern(
                IncidentPattern.Type.DEADLOCK,
                Finding.Severity.CRITICAL,
                IncidentPattern.Confidence.CONFIRMED,
                "Circular lock dependency",
                "A complete waiter-to-owner cycle proves that these threads cannot make progress without intervention.",
                cycle.edges().stream()
                        .map(edge -> edge.waiter().name() + " → " + edge.lock().shortId()
                                + " → " + edge.owner().name())
                        .toList(),
                names(cycle.threads()));
    }

    private IncidentPattern lockConvoy(SynchronizerInsight insight) {
        List<JavaThread> threads = new ArrayList<>(insight.owners());
        threads.addAll(insight.acquisitionWaiters());
        String owner = insight.owners().size() == 1
                ? insight.owners().getFirst().name()
                : "owner unresolved";
        return new IncidentPattern(
                IncidentPattern.Type.LOCK_CONVOY,
                Finding.Severity.WARNING,
                IncidentPattern.Confidence.STRONG_SIGNAL,
                insight.acquisitionWaiters().size() + " threads queue behind one synchronizer",
                "Many threads are trying to acquire the same lock. This is strong contention evidence, not proof that the owner is permanently hung.",
                List.of(
                        "Lock " + insight.lock().id() + " (" + insight.lock().className() + ")",
                        "Observed owner: " + owner,
                        "Acquisition waiters: " + insight.acquisitionWaiters().size()),
                names(threads));
    }

    private IncidentPattern blockingCascade(BlockingImpact impact) {
        return new IncidentPattern(
                IncidentPattern.Type.BLOCKING_CASCADE,
                Finding.Severity.WARNING,
                IncidentPattern.Confidence.STRONG_SIGNAL,
                impact.blocker().name() + " has a " + impact.transitivelyBlocked() + "-thread blast radius",
                "The ownership graph shows downstream dependencies across " + impact.maximumDepth()
                        + " level" + (impact.maximumDepth() == 1 ? "" : "s") + ".",
                List.of(
                        "Directly blocked: " + impact.directlyBlocked(),
                        "Transitively blocked: " + impact.transitivelyBlocked(),
                        "Current frame: " + impact.blocker().topFrame()),
                namesWithFirst(impact.blocker(), impact.affectedThreads()));
    }

    private java.util.Optional<IncidentPattern> detectCohortPattern(StackCohort cohort) {
        String stack = String.join("\n", cohort.stackFrames()).toLowerCase(Locale.ROOT);
        if (looksLikeConnectionPool(stack)) {
            return java.util.Optional.of(cohortPattern(
                    cohort,
                    IncidentPattern.Type.CONNECTION_POOL_EXHAUSTION,
                    "Threads converge on connection acquisition",
                    "Repeated waiting stacks inside a connection-pool acquisition path are a strong exhaustion signal. Confirm pool metrics before changing limits.",
                    IncidentPattern.Confidence.STRONG_SIGNAL));
        }
        if (looksLikeExecutorStarvation(stack) && cohort.threads().stream().allMatch(this::isWaiting)) {
            return java.util.Optional.of(cohortPattern(
                    cohort,
                    IncidentPattern.Type.EXECUTOR_STARVATION,
                    "Workers wait for nested asynchronous work",
                    "Several workers share a Future/join wait path. This can indicate executor starvation; verify pool size, queue depth, and task submission topology.",
                    IncidentPattern.Confidence.SUSPECT));
        }
        if (looksLikeIoRead(stack)) {
            return java.util.Optional.of(cohortPattern(
                    cohort,
                    IncidentPattern.Type.IO_STALL,
                    "Threads converge on the same I/O read path",
                    "Repeated socket or file read stacks indicate an I/O stall candidate. Correlate endpoint latency and JFR events before attributing the cause.",
                    IncidentPattern.Confidence.SUSPECT));
        }
        return java.util.Optional.empty();
    }

    private IncidentPattern cohortPattern(
            StackCohort cohort,
            IncidentPattern.Type type,
            String title,
            String explanation,
            IncidentPattern.Confidence confidence) {
        return new IncidentPattern(
                type,
                Finding.Severity.WARNING,
                confidence,
                title,
                explanation,
                List.of(
                        "Matching threads: " + cohort.threads().size(),
                        "Stack fingerprint: " + cohort.fingerprint(),
                        "Top frame: " + cohort.topFrame()),
                names(cohort.threads()));
    }

    private IncidentPattern cpuHotspot(MethodHotspot hotspot) {
        double totalCpu = hotspot.threads().stream()
                .map(JavaThread::metadata)
                .map(metadata -> metadata.cpuMillis())
                .filter(value -> value != null)
                .mapToDouble(Double::doubleValue)
                .sum();
        return new IncidentPattern(
                IncidentPattern.Type.CPU_HOTSPOT,
                Finding.Severity.WARNING,
                IncidentPattern.Confidence.SUSPECT,
                hotspot.threads().size() + " runnable threads share a CPU-heavy frame",
                "Cumulative CPU telemetry and matching runnable frames make this a hotspot candidate. Use JFR samples to confirm current CPU pressure.",
                List.of(
                        "Top frame: " + hotspot.method(),
                        "Observed cumulative CPU: " + Math.round(totalCpu) + " ms"),
                names(hotspot.threads()));
    }

    private boolean hasMaterialCpuEvidence(MethodHotspot hotspot) {
        return hotspot.threads().stream()
                .map(JavaThread::metadata)
                .map(metadata -> metadata.cpuMillis())
                .filter(value -> value != null)
                .mapToDouble(Double::doubleValue)
                .sum() >= 1_000;
    }

    private boolean isWaiting(JavaThread thread) {
        return Set.of(ThreadState.BLOCKED, ThreadState.WAITING, ThreadState.TIMED_WAITING)
                .contains(thread.state());
    }

    private boolean looksLikeExecutorStarvation(String stack) {
        boolean await = stack.contains("futuretask.get")
                || stack.contains("completablefuture.get")
                || stack.contains("completablefuture.join")
                || stack.contains("countdownlatch.await");
        return await && (stack.contains("threadpoolexecutor")
                || stack.contains("forkjoin")
                || stack.contains("executor"));
    }

    private boolean looksLikeConnectionPool(String stack) {
        return stack.contains("hikaripool.getconnection")
                || stack.contains("datasource.getconnection")
                || stack.contains("connectionpool.borrow")
                || stack.contains("poolableconnectionfactory");
    }

    private boolean looksLikeIoRead(String stack) {
        return stack.contains("socketdispatcher.read")
                || stack.contains("socketinputstream.read")
                || stack.contains("files.read")
                || stack.contains("filedispatcherimpl.read");
    }

    private List<String> names(List<JavaThread> threads) {
        return threads.stream()
                .map(JavaThread::name)
                .distinct()
                .limit(MAX_EVIDENCE_THREADS)
                .toList();
    }

    private List<String> namesWithFirst(JavaThread first, List<JavaThread> rest) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        names.add(first.name());
        rest.stream().map(JavaThread::name).forEach(names::add);
        return names.stream().limit(MAX_EVIDENCE_THREADS).toList();
    }

    private int severityRank(Finding.Severity severity) {
        return switch (severity) {
            case CRITICAL -> 3;
            case WARNING -> 2;
            case INFO -> 1;
        };
    }
}
