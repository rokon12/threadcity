package ca.bazlur.threadcity.application;

import ca.bazlur.threadcity.analysis.SnapshotTrendAnalyzer;
import ca.bazlur.threadcity.domain.AnalysisResult;
import ca.bazlur.threadcity.domain.IncidentPattern;
import ca.bazlur.threadcity.domain.ThreadMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Opt-in verification against a bundle captured from the separate live failure lab.
 */
@EnabledIfSystemProperty(named = "threadcity.bundle", matches = ".+")
class LiveBundleVerifierTest {

    @Test
    void capturedLabBundleFlowsThroughTheRealImportAndAnalysisPipeline() throws Exception {
        Path path = Path.of(System.getProperty("threadcity.bundle"));
        var bundle = new IncidentBundleService().read(path.getFileName().toString(), Files.readAllBytes(path));
        ThreadDumpAnalysisService dumpService = new ThreadDumpAnalysisService();
        List<AnalysisResult> snapshots = bundle.threadDumps().stream()
                .map(dump -> dumpService.analyze(dump.sourceName(), dump.content()))
                .toList();
        var jfr = new JfrAnalysisService().analyze("recording.jfr", bundle.jfrRecording());
        var allThreads = bundle.allThreads()
                .map(dump -> dumpService.analyzeJava25(
                        dump.sourceName(), dump.content().getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .orElseThrow();

        assertThat(snapshots).hasSizeBetween(2, 5);
        assertThat(snapshots).anyMatch(AnalysisResult::hasDeadlock);
        assertThat(snapshots.getLast().snapshot().threads())
                .extracting(thread -> thread.name())
                .contains("lab-deadlock-payment", "lab-deadlock-inventory", "lab-convoy-owner")
                .anyMatch(name -> name.startsWith("lab-starved-worker-"))
                .anyMatch(name -> name.startsWith("lab-db-waiter-"))
                .anyMatch(name -> name.startsWith("lab-socket-reader-"));
        assertThat(snapshots.getLast().patterns()).extracting(IncidentPattern::type)
                .contains(IncidentPattern.Type.DEADLOCK, IncidentPattern.Type.LOCK_CONVOY);
        assertThat(new SnapshotTrendAnalyzer().findPersistentStalls(snapshots)).isNotEmpty();
        assertThat(jfr.samples()).anyMatch(sample ->
                sample.eventType().equals("threadcity.lab.PressurePulse"));
        assertThat(allThreads.snapshot().threads())
                .anyMatch(thread -> thread.metadata().kind() == ThreadMetadata.ThreadKind.VIRTUAL);
    }
}
