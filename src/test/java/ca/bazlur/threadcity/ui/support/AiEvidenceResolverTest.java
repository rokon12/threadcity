package ca.bazlur.threadcity.ui.support;

import ca.bazlur.threadcity.analysis.ThreadDumpAnalyzer;
import ca.bazlur.threadcity.domain.AnalysisResult;
import ca.bazlur.threadcity.parser.HotSpotThreadDumpParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class AiEvidenceResolverTest {

    @Test
    void resolvesThreadsLocksAndFindingsMentionedByTheCopilot() throws IOException {
        AnalysisResult result = result();

        var references = AiEvidenceResolver.resolve(
                result,
                "What blocks checkout-37?",
                "checkout-37 waits for 1a77b210. Circular lock dependency detected is the key finding.");

        assertThat(references)
                .extracting(AiEvidenceReference::kind)
                .contains(
                        AiEvidenceReference.Kind.THREAD,
                        AiEvidenceReference.Kind.LOCK,
                        AiEvidenceReference.Kind.FINDING);
        assertThat(references)
                .extracting(AiEvidenceReference::key)
                .contains("checkout-37", "1a77b210", "Circular lock dependency detected");
    }

    @Test
    void doesNotCreateLinksForInventedEvidence() throws IOException {
        assertThat(AiEvidenceResolver.resolve(result(), "What happened?", "A database timeout occurred."))
                .isEmpty();
    }

    private AnalysisResult result() throws IOException {
        try (var stream = getClass().getResourceAsStream("/samples/deadlock.txt")) {
            assertThat(stream).isNotNull();
            String dump = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            return new ThreadDumpAnalyzer().analyze(new HotSpotThreadDumpParser().parse("sample", dump));
        }
    }
}
