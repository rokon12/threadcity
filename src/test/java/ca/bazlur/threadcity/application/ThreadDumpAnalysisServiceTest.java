package ca.bazlur.threadcity.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ThreadDumpAnalysisServiceTest {

    private final ThreadDumpAnalysisService service = new ThreadDumpAnalysisService();

    @Test
    void analyzesTheBuiltInDeadlockThroughTheApplicationBoundary() {
        var result = service.analyzeSample("demo", "deadlock.txt");

        assertThat(result.snapshot().threads()).hasSize(6);
        assertThat(result.waitEdges()).hasSize(2);
        assertThat(result.deadlocks()).hasSize(1);
    }

    @Test
    void failsClearlyWhenABuiltInFixtureIsMissing() {
        assertThatThrownBy(() -> service.analyzeSample("demo", "missing.txt"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing.txt");
    }
}
