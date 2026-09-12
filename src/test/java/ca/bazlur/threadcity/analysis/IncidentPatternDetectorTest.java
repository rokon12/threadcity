package ca.bazlur.threadcity.analysis;

import ca.bazlur.threadcity.domain.AnalysisResult;
import ca.bazlur.threadcity.domain.IncidentPattern;
import ca.bazlur.threadcity.domain.PersistentThread;
import ca.bazlur.threadcity.parser.HotSpotThreadDumpParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IncidentPatternDetectorTest {

    private final HotSpotThreadDumpParser parser = new HotSpotThreadDumpParser();
    private final ThreadDumpAnalyzer analyzer = new ThreadDumpAnalyzer();

    @Test
    void detectsLockConvoyAndBlockingCascadeFromOwnershipEvidence() {
        String dump = """
                "convoy-owner" #1
                   java.lang.Thread.State: RUNNABLE
                    at example.Checkout.charge(Checkout.java:10)
                    - locked <0x1> (a example.CheckoutLock)
                "waiter-1" #2
                   java.lang.Thread.State: BLOCKED
                    - waiting to lock <0x1> (a example.CheckoutLock)
                "waiter-2" #3
                   java.lang.Thread.State: BLOCKED
                    - waiting to lock <0x1> (a example.CheckoutLock)
                "waiter-3" #4
                   java.lang.Thread.State: BLOCKED
                    - waiting to lock <0x1> (a example.CheckoutLock)
                "waiter-4" #5
                   java.lang.Thread.State: BLOCKED
                    - waiting to lock <0x1> (a example.CheckoutLock)
                """;

        AnalysisResult result = analyze("convoy", dump);

        assertThat(result.patterns()).extracting(IncidentPattern::type)
                .contains(IncidentPattern.Type.LOCK_CONVOY, IncidentPattern.Type.BLOCKING_CASCADE);
        assertThat(result.patterns())
                .filteredOn(pattern -> pattern.type() == IncidentPattern.Type.LOCK_CONVOY)
                .singleElement()
                .satisfies(pattern -> {
                    assertThat(pattern.confidence()).isEqualTo(IncidentPattern.Confidence.STRONG_SIGNAL);
                    assertThat(pattern.evidence()).anyMatch(line -> line.contains("Acquisition waiters: 4"));
                });
    }

    @Test
    void labelsExecutorStarvationAsSuspectInsteadOfFact() {
        String dump = """
                "pool-worker-1" #1
                   java.lang.Thread.State: WAITING
                    at java.util.concurrent.FutureTask.get(FutureTask.java:190)
                    at java.util.concurrent.ThreadPoolExecutor$Worker.run(ThreadPoolExecutor.java:635)
                "pool-worker-2" #2
                   java.lang.Thread.State: WAITING
                    at java.util.concurrent.FutureTask.get(FutureTask.java:190)
                    at java.util.concurrent.ThreadPoolExecutor$Worker.run(ThreadPoolExecutor.java:635)
                "pool-worker-3" #3
                   java.lang.Thread.State: WAITING
                    at java.util.concurrent.FutureTask.get(FutureTask.java:190)
                    at java.util.concurrent.ThreadPoolExecutor$Worker.run(ThreadPoolExecutor.java:635)
                """;

        AnalysisResult result = analyze("executor", dump);

        assertThat(result.patterns()).singleElement().satisfies(pattern -> {
            assertThat(pattern.type()).isEqualTo(IncidentPattern.Type.EXECUTOR_STARVATION);
            assertThat(pattern.confidence()).isEqualTo(IncidentPattern.Confidence.SUSPECT);
            assertThat(pattern.explanation()).contains("verify");
        });
    }

    @Test
    void requiresCpuTelemetryBeforeCallingARepeatedRunnableFrameAHotspot() {
        String noCpu = runnableCohort("");
        String withCpu = runnableCohort(" cpu=750ms");

        assertThat(analyze("no-cpu", noCpu).patterns())
                .noneMatch(pattern -> pattern.type() == IncidentPattern.Type.CPU_HOTSPOT);
        assertThat(analyze("cpu", withCpu).patterns())
                .anyMatch(pattern -> pattern.type() == IncidentPattern.Type.CPU_HOTSPOT
                        && pattern.confidence() == IncidentPattern.Confidence.SUSPECT);
    }

    @Test
    void reportsAStallCandidateOnlyAfterThreeIdenticalSuspiciousSnapshots() {
        String blocked = """
                "request-17" #17
                   java.lang.Thread.State: BLOCKED
                    at example.Checkout.enter(Checkout.java:4)
                    - waiting to lock <0x1> (a example.CheckoutLock)
                "owner" #18
                   java.lang.Thread.State: RUNNABLE
                    at example.Checkout.hold(Checkout.java:9)
                    - locked <0x1> (a example.CheckoutLock)
                """;
        List<AnalysisResult> snapshots = List.of(
                analyze("T-10", blocked), analyze("T-5", blocked), analyze("T0", blocked));

        assertThat(new SnapshotTrendAnalyzer().findPersistentStalls(snapshots))
                .singleElement()
                .extracting(PersistentThread::threadName)
                .isEqualTo("request-17");
        assertThat(new SnapshotTrendAnalyzer().findPersistentStalls(snapshots.subList(0, 2))).isEmpty();
    }

    private AnalysisResult analyze(String source, String dump) {
        return analyzer.analyze(parser.parse(source, dump));
    }

    private String runnableCohort(String telemetry) {
        return """
                "spinner-1" #1%s
                   java.lang.Thread.State: RUNNABLE
                    at example.Spinner.spin(Spinner.java:7)
                "spinner-2" #2%s
                   java.lang.Thread.State: RUNNABLE
                    at example.Spinner.spin(Spinner.java:7)
                "spinner-3" #3%s
                   java.lang.Thread.State: RUNNABLE
                    at example.Spinner.spin(Spinner.java:7)
                """.formatted(telemetry, telemetry, telemetry);
    }
}
