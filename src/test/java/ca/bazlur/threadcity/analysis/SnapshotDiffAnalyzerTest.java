package ca.bazlur.threadcity.analysis;

import ca.bazlur.threadcity.domain.AnalysisResult;
import ca.bazlur.threadcity.domain.SnapshotDiff;
import ca.bazlur.threadcity.domain.ThreadChange;
import ca.bazlur.threadcity.parser.HotSpotThreadDumpParser;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SnapshotDiffAnalyzerTest {

    private final HotSpotThreadDumpParser parser = new HotSpotThreadDumpParser();
    private final ThreadDumpAnalyzer analyzer = new ThreadDumpAnalyzer();
    private final SnapshotDiffAnalyzer diffAnalyzer = new SnapshotDiffAnalyzer();

    @Test
    void comparesThreadLifecycleStateStackWaitsAndDeadlocks() {
        AnalysisResult before = analyze("before", """
                "owner" #1
                   java.lang.Thread.State: RUNNABLE
                    at example.Owner.before(Owner.java:1)
                    - locked <0x1> (a example.Lock)
                "waiter" #2
                   java.lang.Thread.State: BLOCKED
                    at example.Waiter.before(Waiter.java:2)
                    - waiting to lock <0x1> (a example.Lock)
                "removed" #3
                   java.lang.Thread.State: WAITING
                """
        );
        AnalysisResult after = analyze("after", """
                "owner" #1
                   java.lang.Thread.State: BLOCKED
                    at example.Owner.after(Owner.java:9)
                    - waiting to lock <0x2> (a example.OtherLock)
                    - locked <0x1> (a example.Lock)
                "waiter" #2
                   java.lang.Thread.State: BLOCKED
                    at example.Waiter.before(Waiter.java:2)
                    - waiting to lock <0x1> (a example.Lock)
                    - locked <0x2> (a example.OtherLock)
                "added" #4
                   java.lang.Thread.State: RUNNABLE
                """
        );

        SnapshotDiff diff = diffAnalyzer.compare(before, after);

        assertThat(diff.newWaitEdges()).isEqualTo(1);
        assertThat(diff.resolvedWaitEdges()).isZero();
        assertThat(diff.persistentWaitEdges()).isEqualTo(1);
        assertThat(diff.newDeadlocks()).isEqualTo(1);
        assertThat(diff.resolvedDeadlocks()).isZero();
        assertThat(diff.threadChanges()).filteredOn(change -> change.threadName().equals("owner"))
                .singleElement().satisfies(change -> assertThat(change.kinds())
                        .containsExactlyInAnyOrder(
                                ThreadChange.Kind.STATE_CHANGED,
                                ThreadChange.Kind.WAIT_CHANGED,
                                ThreadChange.Kind.STACK_CHANGED));
        assertThat(diff.threadChanges()).filteredOn(change -> change.threadName().equals("removed"))
                .singleElement().satisfies(change -> assertThat(change.kinds())
                        .containsExactly(ThreadChange.Kind.REMOVED));
        assertThat(diff.threadChanges()).filteredOn(change -> change.threadName().equals("added"))
                .singleElement().satisfies(change -> assertThat(change.kinds())
                        .containsExactly(ThreadChange.Kind.ADDED));
    }

    @Test
    void keepsDuplicateThreadNamesAsSeparateOccurrences() {
        AnalysisResult before = analyze("before", """
                "worker" #1
                   java.lang.Thread.State: RUNNABLE
                "worker" #2
                   java.lang.Thread.State: WAITING
                """
        );
        AnalysisResult after = analyze("after", """
                "worker" #1
                   java.lang.Thread.State: RUNNABLE
                "worker" #2
                   java.lang.Thread.State: BLOCKED
                """
        );

        SnapshotDiff diff = diffAnalyzer.compare(before, after);

        assertThat(diff.threadChanges()).extracting(ThreadChange::identity)
                .containsExactly("worker · Java #2", "worker · Java #1");
        assertThat(diff.threadChanges()).filteredOn(ThreadChange::changed).singleElement()
                .satisfies(change -> assertThat(change.identity()).isEqualTo("worker · Java #2"));
    }

    private AnalysisResult analyze(String name, String dump) {
        return analyzer.analyze(parser.parse(name, dump));
    }
}
