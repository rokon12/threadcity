package ca.bazlur.threadcity.parser;

import ca.bazlur.threadcity.analysis.ThreadDumpAnalyzer;
import ca.bazlur.threadcity.domain.ThreadMetadata;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Java25ThreadDumpParserTest {

    private final Java25ThreadDumpParser parser = new Java25ThreadDumpParser();

    @Test
    void parsesPlatformVirtualAndMonitorEvidence() {
        var snapshot = parser.parse("all-threads.json", json().getBytes(StandardCharsets.UTF_8));
        var result = new ThreadDumpAnalyzer().analyze(snapshot);

        assertThat(snapshot.threads()).hasSize(3);
        assertThat(snapshot.threads()).filteredOn(thread -> thread.metadata().kind()
                        == ThreadMetadata.ThreadKind.VIRTUAL)
                .singleElement()
                .satisfies(thread -> {
                    assertThat(thread.name()).isEqualTo("virtual-request");
                    assertThat(thread.metadata().javaThreadNumber()).isEqualTo(81L);
                });
        assertThat(result.deadlocks()).singleElement().satisfies(cycle ->
                assertThat(cycle.threads()).extracting(thread -> thread.name())
                        .containsExactly("payment", "inventory"));
    }

    @Test
    void rejectsJsonThatIsNotAJavaThreadDump() {
        assertThatThrownBy(() -> parser.parse("bad.json", "{}".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("threadDump structure");
    }

    private static String json() {
        return """
                {"threadDump":{"processId":"42","threadContainers":[{"container":"<root>","threads":[
                  {"tid":"25","name":"payment","state":"BLOCKED",
                   "blockedOn":"example.InventoryLock@2","stack":["example.Payment.run(Payment.java:1)"],
                   "monitorsOwned":[{"depth":0,"locks":["example.PaymentLock@1"]}]},
                  {"tid":"26","name":"inventory","state":"BLOCKED",
                   "blockedOn":"example.PaymentLock@1","stack":["example.Inventory.run(Inventory.java:1)"],
                   "monitorsOwned":[{"depth":0,"locks":["example.InventoryLock@2"]}]},
                  {"tid":"81","name":"virtual-request","virtual":true,"state":"WAITING",
                   "stack":["java.util.concurrent.Future.get(Future.java:1)"]}
                ]}]}}
                """;
    }
}
