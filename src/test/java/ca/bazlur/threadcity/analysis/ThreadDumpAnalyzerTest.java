package ca.bazlur.threadcity.analysis;

import ca.bazlur.threadcity.domain.AnalysisResult;
import ca.bazlur.threadcity.domain.Finding;
import ca.bazlur.threadcity.domain.SynchronizerInsight;
import ca.bazlur.threadcity.parser.HotSpotThreadDumpParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class ThreadDumpAnalyzerTest {

    private final HotSpotThreadDumpParser parser = new HotSpotThreadDumpParser();
    private final ThreadDumpAnalyzer analyzer = new ThreadDumpAnalyzer();

    @Test
    void detectsCircularWaitAndRepeatedStackCluster() throws IOException {
        AnalysisResult result = analyzer.analyze(parser.parse("sample", sampleDump()));

        assertThat(result.waitEdges()).hasSize(2);
        assertThat(result.deadlocks()).hasSize(1);
        assertThat(result.deadlocks().getFirst().threads())
                .extracting(thread -> thread.name())
                .containsExactly("checkout-37", "inventory-sync-12");
        assertThat(result.stackClusters()).singleElement()
                .satisfies(cluster -> assertThat(cluster.threads()).hasSize(3));
        assertThat(result.stackCohorts()).hasSize(4);
        assertThat(result.stackCohorts().getFirst()).satisfies(cohort -> {
            assertThat(cohort.fingerprint()).hasSize(12);
            assertThat(cohort.threads()).hasSize(3);
            assertThat(cohort.repeated()).isTrue();
        });
        assertThat(result.methodHotspots()).singleElement()
                .satisfies(hotspot -> assertThat(hotspot.threads().getFirst().name())
                        .isEqualTo("reference-handler"));
        assertThat(result.findings())
                .extracting(Finding::severity)
                .contains(Finding.Severity.CRITICAL, Finding.Severity.INFO);
    }

    @Test
    void doesNotReportADeadlockForAnUnownedWait() {
        String dump = """
                \"worker\" #1
                   java.lang.Thread.State: WAITING
                    at example.Worker.run(Worker.java:1)
                    - parking to wait for <0x0000000000000001> (a java.util.concurrent.locks.Lock)
                """;

        AnalysisResult result = analyzer.analyze(parser.parse("single", dump));

        assertThat(result.waitEdges()).isEmpty();
        assertThat(result.deadlocks()).isEmpty();
        assertThat(result.findings()).singleElement()
                .satisfies(finding -> assertThat(finding.title()).contains("No confirmed deadlock"));
    }

    @Test
    void objectWaitDoesNotBecomeAnOwnershipEdge() {
        String dump = """
                "condition-waiter" #1
                   java.lang.Thread.State: WAITING
                    - waiting on <0x1> (a java.lang.Object)
                "monitor-owner" #2
                   java.lang.Thread.State: RUNNABLE
                    - locked <0x1> (a java.lang.Object)
                """;

        AnalysisResult result = analyzer.analyze(parser.parse("object-wait", dump));

        assertThat(result.waitEdges()).isEmpty();
        assertThat(result.deadlocks()).isEmpty();
    }

    @Test
    void ambiguousOwnershipDoesNotProduceAConfidentEdge() {
        String dump = """
                "waiter" #1
                   java.lang.Thread.State: BLOCKED
                    - waiting to lock <0x1> (a java.lang.Object)
                "owner-one" #2
                   java.lang.Thread.State: RUNNABLE
                    - locked <0x1> (a java.lang.Object)
                "owner-two" #3
                   java.lang.Thread.State: RUNNABLE
                    - locked <0x1> (a java.lang.Object)
                """;

        AnalysisResult result = analyzer.analyze(parser.parse("ambiguous", dump));

        assertThat(result.waitEdges()).isEmpty();
        assertThat(result.deadlocks()).isEmpty();
    }

    @Test
    void detectsAThreeThreadCycleInStableOrder() {
        String dump = """
                "alpha" #1
                   java.lang.Thread.State: BLOCKED
                    - waiting to lock <0x2> (a java.lang.Object)
                    - locked <0x1> (a java.lang.Object)
                "beta" #2
                   java.lang.Thread.State: BLOCKED
                    - waiting to lock <0x3> (a java.lang.Object)
                    - locked <0x2> (a java.lang.Object)
                "gamma" #3
                   java.lang.Thread.State: BLOCKED
                    - waiting to lock <0x1> (a java.lang.Object)
                    - locked <0x3> (a java.lang.Object)
                """;

        AnalysisResult result = analyzer.analyze(parser.parse("three-way", dump));

        assertThat(result.deadlocks()).singleElement().satisfies(cycle ->
                assertThat(cycle.threads()).extracting(thread -> thread.name())
                        .containsExactly("alpha", "beta", "gamma"));
    }

    @Test
    void correctedFixtureHasNoDeadlock() throws IOException {
        String dump;
        try (var stream = getClass().getResourceAsStream("/samples/corrected.txt")) {
            assertThat(stream).isNotNull();
            dump = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }

        AnalysisResult result = analyzer.analyze(parser.parse("corrected", dump));

        assertThat(result.snapshot().threads()).hasSize(6);
        assertThat(result.waitEdges()).isEmpty();
        assertThat(result.deadlocks()).isEmpty();
    }

    @Test
    void contentionFixtureShowsPressureWithoutClaimingADeadlock() throws IOException {
        String dump;
        try (var stream = getClass().getResourceAsStream("/samples/contention.txt")) {
            assertThat(stream).isNotNull();
            dump = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }

        AnalysisResult result = analyzer.analyze(parser.parse("contention", dump));

        assertThat(result.snapshot().threads()).hasSize(6);
        assertThat(result.waitEdges()).singleElement().satisfies(edge -> {
            assertThat(edge.waiter().name()).isEqualTo("checkout-37");
            assertThat(edge.owner().name()).isEqualTo("inventory-sync-12");
        });
        assertThat(result.deadlocks()).isEmpty();
    }

    @Test
    void ranksBlockersByTransitiveBlastRadiusAndDepth() {
        String dump = """
                "root-owner" #1
                   java.lang.Thread.State: RUNNABLE
                    - locked <0x1> (a example.RootLock)
                "middle-owner" #2
                   java.lang.Thread.State: BLOCKED
                    - waiting to lock <0x1> (a example.RootLock)
                    - locked <0x2> (a example.MiddleLock)
                "leaf" #3
                   java.lang.Thread.State: BLOCKED
                    - waiting to lock <0x2> (a example.MiddleLock)
                "sibling" #4
                   java.lang.Thread.State: BLOCKED
                    - waiting to lock <0x1> (a example.RootLock)
                "independent" #5
                   java.lang.Thread.State: RUNNABLE
                """;

        AnalysisResult result = analyzer.analyze(parser.parse("cascade", dump));

        assertThat(result.blockingImpacts()).hasSize(2);
        assertThat(result.blockingImpacts().getFirst()).satisfies(impact -> {
            assertThat(impact.blocker().name()).isEqualTo("root-owner");
            assertThat(impact.directlyBlocked()).isEqualTo(2);
            assertThat(impact.transitivelyBlocked()).isEqualTo(3);
            assertThat(impact.maximumDepth()).isEqualTo(2);
            assertThat(impact.affectedThreads())
                    .extracting(thread -> thread.name())
                    .containsExactly("middle-owner", "sibling", "leaf");
        });
        assertThat(result.blockingImpacts().get(1)).satisfies(impact -> {
            assertThat(impact.blocker().name()).isEqualTo("middle-owner");
            assertThat(impact.transitivelyBlocked()).isEqualTo(1);
            assertThat(impact.maximumDepth()).isEqualTo(1);
        });
    }

    @Test
    void crossReferencesSynchronizerOwnersWaitersRiskAndImpact() {
        String dump = """
                "root-owner" #1
                   java.lang.Thread.State: RUNNABLE
                    - locked <0x1> (a example.RootLock)
                "acquisition-waiter" #2
                   java.lang.Thread.State: BLOCKED
                    - waiting to lock <0x1> (a example.RootLock)
                "condition-waiter" #3
                   java.lang.Thread.State: WAITING
                    - waiting on <0x2> (a example.Condition)
                "unresolved-waiter" #4
                   java.lang.Thread.State: BLOCKED
                    - waiting to lock <0x3> (a example.MissingOwner)
                """;

        AnalysisResult result = analyzer.analyze(parser.parse("synchronizers", dump));

        assertThat(result.synchronizers()).hasSize(3);
        assertThat(result.synchronizers().getFirst()).satisfies(insight -> {
            assertThat(insight.lock().id()).isEqualTo("0x1");
            assertThat(insight.risk()).isEqualTo(SynchronizerInsight.Risk.CONTENDED);
            assertThat(insight.owners()).extracting(thread -> thread.name()).containsExactly("root-owner");
            assertThat(insight.acquisitionWaiters())
                    .extracting(thread -> thread.name()).containsExactly("acquisition-waiter");
            assertThat(insight.downstreamImpact()).isEqualTo(1);
        });
        assertThat(result.synchronizers())
                .filteredOn(insight -> insight.lock().id().equals("0x3"))
                .singleElement()
                .satisfies(insight -> assertThat(insight.risk())
                        .isEqualTo(SynchronizerInsight.Risk.UNRESOLVED));
        assertThat(result.synchronizers())
                .filteredOn(insight -> insight.lock().id().equals("0x2"))
                .singleElement()
                .satisfies(insight -> {
                    assertThat(insight.risk()).isEqualTo(SynchronizerInsight.Risk.NOTIFICATION);
                    assertThat(insight.notificationWaiters())
                            .extracting(thread -> thread.name()).containsExactly("condition-waiter");
                });
    }

    @Test
    void groupsRunnableThreadsByCurrentTopFrame() {
        String dump = """
                "runner-one" #1
                   java.lang.Thread.State: RUNNABLE
                    at example.Shared.poll(Shared.java:10)
                "runner-two" #2
                   java.lang.Thread.State: RUNNABLE
                    at example.Shared.poll(Shared.java:10)
                "waiting" #3
                   java.lang.Thread.State: WAITING
                    at example.Shared.poll(Shared.java:10)
                """;

        AnalysisResult result = analyzer.analyze(parser.parse("methods", dump));

        assertThat(result.stackCohorts()).singleElement()
                .satisfies(cohort -> assertThat(cohort.threads()).hasSize(3));
        assertThat(result.methodHotspots()).singleElement().satisfies(hotspot -> {
            assertThat(hotspot.method()).isEqualTo("at example.Shared.poll(Shared.java:10)");
            assertThat(hotspot.threads()).extracting(thread -> thread.name())
                    .containsExactly("runner-one", "runner-two");
        });
    }

    private String sampleDump() throws IOException {
        try (var stream = getClass().getResourceAsStream("/samples/deadlock.txt")) {
            assertThat(stream).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
