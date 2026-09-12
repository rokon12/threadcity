package ca.bazlur.threadcity.application;

import ca.bazlur.threadcity.analysis.ThreadDumpAnalyzer;
import ca.bazlur.threadcity.domain.AnalysisResult;
import ca.bazlur.threadcity.parser.HotSpotThreadDumpParser;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class IncidentReportServiceTest {

    @Test
    void createsPortableEvidenceReportAndEscapesUploadedText() {
        String dump = """
                "request-<script>alert(1)</script>" #1
                   java.lang.Thread.State: BLOCKED
                    at example.Checkout.enter(Checkout.java:4)
                    - waiting to lock <0x1> (a example.Lock)
                "owner" #2
                   java.lang.Thread.State: RUNNABLE
                    at example.Owner.hold(Owner.java:9)
                    - locked <0x1> (a example.Lock)
                """;
        AnalysisResult result = new ThreadDumpAnalyzer().analyze(
                new HotSpotThreadDumpParser().parse("checkout <img src=x>", dump));

        String report = new String(
                new IncidentReportService().create(result, Optional.empty()), StandardCharsets.UTF_8);

        assertThat(report)
                .contains("ThreadCity incident dossier", "Wait-for evidence", "Verification checklist")
                .contains("request-&lt;script&gt;alert(1)&lt;/script&gt;")
                .contains("checkout &lt;img src=x&gt;")
                .doesNotContain("<script>alert(1)</script>", "<img src=x>")
                .contains("default-src 'none'");
    }
}
