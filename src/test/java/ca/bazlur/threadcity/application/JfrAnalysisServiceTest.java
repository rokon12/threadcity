package ca.bazlur.threadcity.application;

import ca.bazlur.threadcity.domain.JfrEventCategory;
import ca.bazlur.threadcity.domain.JfrAnalysis;
import jdk.jfr.Event;
import jdk.jfr.Name;
import jdk.jfr.Recording;
import jdk.jfr.StackTrace;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JfrAnalysisServiceTest {

    private final JfrAnalysisService service = new JfrAnalysisService();

    @Test
    void createsARealDemoRecordingWithDumpCorrelatableThreadNames() {
        JfrAnalysis analysis = service.analyzeDemo();

        assertThat(analysis.relevantEvents()).isGreaterThanOrEqualTo(7);
        assertThat(analysis.samples()).extracting(sample -> sample.threadName())
                .contains("checkout-37", "inventory-sync-12");
        assertThat(analysis.samples()).allMatch(sample -> sample.eventType().startsWith("threadcity.lab."));
    }

    @Test
    void readsBoundedRelevantEventsFromARealRecording() throws Exception {
        Path file = Files.createTempFile("threadcity-jfr-test-", ".jfr");
        try {
            try (Recording recording = new Recording()) {
                recording.enable(LabPulse.class).withStackTrace();
                recording.start();
                LabPulse pulse = new LabPulse();
                pulse.scenario = "lock-convoy";
                pulse.pressure = 7;
                pulse.begin();
                Thread.sleep(3);
                pulse.commit();
                recording.stop();
                recording.dump(file);
            }

            var analysis = service.analyze("lab.jfr", Files.readAllBytes(file));

            assertThat(analysis.eventsRead()).isEqualTo(1);
            assertThat(analysis.relevantEvents()).isEqualTo(1);
            assertThat(analysis.truncated()).isFalse();
            assertThat(analysis.samples()).singleElement().satisfies(sample -> {
                assertThat(sample.category()).isEqualTo(JfrEventCategory.LAB);
                assertThat(sample.eventType()).isEqualTo("threadcity.lab.TestPulse");
                assertThat(sample.threadName()).isEqualTo(Thread.currentThread().getName());
                assertThat(sample.detail()).contains("lock-convoy");
            });
            assertThat(analysis.summaries()).singleElement()
                    .satisfies(summary -> assertThat(summary.count()).isEqualTo(1));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void rejectsNonJfrInputBeforeInvokingTheJdkParser() {
        assertThatThrownBy(() -> service.analyze("fake.jfr", "not-jfr".getBytes()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JFR header");
    }

    @Name("threadcity.lab.TestPulse")
    @StackTrace(true)
    static final class LabPulse extends Event {
        String scenario;
        int pressure;
    }
}
