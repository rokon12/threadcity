package ca.bazlur.threadcity.parser;

import ca.bazlur.threadcity.domain.LockWaitKind;
import ca.bazlur.threadcity.domain.ParserDiagnostics;
import ca.bazlur.threadcity.domain.ThreadSnapshot;
import ca.bazlur.threadcity.domain.ThreadState;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HotSpotThreadDumpParserTest {

    private final HotSpotThreadDumpParser parser = new HotSpotThreadDumpParser();

    @Test
    void parsesThreadsStatesStacksAndLocks() throws IOException {
        ThreadSnapshot snapshot = parser.parse("sample", sampleDump());

        assertThat(snapshot.threads()).hasSize(6);
        assertThat(snapshot.threads().getFirst().name()).isEqualTo("checkout-37");
        assertThat(snapshot.threads().getFirst().state()).isEqualTo(ThreadState.BLOCKED);
        assertThat(snapshot.threads().getFirst().stackFrames()).hasSize(3);
        assertThat(snapshot.threads().getFirst().waitingOn().id()).isEqualTo("0x000000061a77b210");
        assertThat(snapshot.threads().getFirst().ownedLocks())
                .extracting(lock -> lock.id())
                .containsExactly("0x000000061a77b180");
        assertThat(snapshot.parserDiagnostics().confidence()).isEqualTo(ParserDiagnostics.Confidence.HIGH);
        assertThat(snapshot.parserDiagnostics().coveragePercent()).isEqualTo(100);
    }

    @Test
    void rejectsContentWithoutThreadHeaders() {
        assertThatThrownBy(() -> parser.parse("bad", "not a thread dump"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No HotSpot thread headers");
    }

    @Test
    void rejectsEmptyContent() {
        assertThatThrownBy(() -> parser.parse("empty", "  \n\t"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty");
    }

    @Test
    void parsesCrLfAndDaemonHeaders() {
        String dump = "\"daemon-worker\" #7 daemon prio=5\r\n"
                + "   java.lang.Thread.State: RUNNABLE\r\n"
                + "        at example.Worker.run(Worker.java:7)\r\n";

        ThreadSnapshot snapshot = parser.parse("crlf", dump);

        assertThat(snapshot.threads()).singleElement().satisfies(thread -> {
            assertThat(thread.name()).isEqualTo("daemon-worker");
            assertThat(thread.state()).isEqualTo(ThreadState.RUNNABLE);
            assertThat(thread.stackFrames()).containsExactly("at example.Worker.run(Worker.java:7)");
        });
    }

    @Test
    void unescapesQuotedAndBackslashedThreadNames() {
        String dump = "\"worker\\\"east\\\\one\" #1\n"
                + "   java.lang.Thread.State: RUNNABLE\n";

        ThreadSnapshot snapshot = parser.parse("escaped", dump);

        assertThat(snapshot.threads().getFirst().name()).isEqualTo("worker\"east\\one");
    }

    @Test
    void parsesCommonThreadStates() {
        String dump = """
                "run" #1
                   java.lang.Thread.State: RUNNABLE
                "blocked" #2
                   java.lang.Thread.State: BLOCKED (on object monitor)
                "waiting" #3
                   java.lang.Thread.State: WAITING (parking)
                "timed" #4
                   java.lang.Thread.State: TIMED_WAITING (sleeping)
                """;

        assertThat(parser.parse("states", dump).threads())
                .extracting(thread -> thread.state())
                .containsExactly(
                        ThreadState.RUNNABLE,
                        ThreadState.BLOCKED,
                        ThreadState.WAITING,
                        ThreadState.TIMED_WAITING);
    }

    @Test
    void distinguishesMonitorEntryObjectWaitAndParking() {
        String dump = """
                "monitor-entry" #1
                   java.lang.Thread.State: BLOCKED
                    - waiting to lock <0x1> (a java.lang.Object)
                "object-wait" #2
                   java.lang.Thread.State: WAITING
                    - waiting on <0x2> (a java.lang.Object)
                "parked" #3
                   java.lang.Thread.State: WAITING
                    - parking to wait for <0x3> (a java.util.concurrent.locks.ReentrantLock$NonfairSync)
                """;

        ThreadSnapshot snapshot = parser.parse("waits", dump);

        assertThat(snapshot.threads())
                .extracting(thread -> thread.waitKind())
                .containsExactly(LockWaitKind.MONITOR_ENTRY, LockWaitKind.OBJECT_WAIT, LockWaitKind.PARKING);
    }

    @Test
    void parsesLockedOwnableSynchronizers() {
        String dump = """
                "lock-owner" #1
                   java.lang.Thread.State: WAITING (parking)
                Locked ownable synchronizers:
                    - <0x0000000000000042> (a java.util.concurrent.locks.ReentrantLock$NonfairSync)
                """;

        assertThat(parser.parse("ownable", dump).threads().getFirst().ownedLocks())
                .singleElement()
                .satisfies(lock -> assertThat(lock.id()).isEqualTo("0x0000000000000042"));
    }

    @Test
    void assignsIdentityIndependentlyOfDuplicateNamesAndAcceptsTruncation() {
        String dump = """
                "worker" #1
                   java.lang.Thread.State: RUNNABLE
                    at example.First.run(First.java:1)
                "worker" #2
                   java.lang.Thread.State: UNKNOWN_FUTURE_STATE
                    at example.Second.run(Second.java:2)
                """;

        ThreadSnapshot snapshot = parser.parse("truncated", dump);

        assertThat(snapshot.threads()).extracting(thread -> thread.id()).containsExactly(0, 1);
        assertThat(snapshot.threads()).extracting(thread -> thread.name()).containsExactly("worker", "worker");
        assertThat(snapshot.threads().get(1).state()).isEqualTo(ThreadState.UNKNOWN);
    }

    @Test
    void reportsBoundedIgnoredInputAndCoverage() {
        StringBuilder dump = new StringBuilder("""
                2026-09-12 10:15:30
                Full thread dump OpenJDK 64-Bit Server VM:
                "worker" #1
                   java.lang.Thread.State: RUNNABLE
                    at example.Worker.run(Worker.java:1)
                diagnostic extension one
                diagnostic extension one
                """);
        for (int index = 0; index < 55; index++) {
            dump.append("unknown-line-").append(index).append('\n');
        }

        ThreadSnapshot snapshot = parser.parse("diagnostics", dump.toString());

        assertThat(snapshot.parserDiagnostics()).satisfies(diagnostics -> {
            assertThat(diagnostics.contentLines()).isEqualTo(62);
            assertThat(diagnostics.recognizedLines()).isEqualTo(5);
            assertThat(diagnostics.ignoredLines()).isEqualTo(57);
            assertThat(diagnostics.ignoredLineSamples()).hasSize(50);
            assertThat(diagnostics.ignoredLineSamples().getFirst().occurrences()).isEqualTo(2);
            assertThat(diagnostics.omittedIgnoredLines()).isEqualTo(6);
            assertThat(diagnostics.confidence()).isEqualTo(ParserDiagnostics.Confidence.LOW);
        });
    }

    private String sampleDump() throws IOException {
        try (var stream = getClass().getResourceAsStream("/samples/deadlock.txt")) {
            assertThat(stream).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
