package ca.bazlur.threadcity.ui;

import ca.bazlur.threadcity.application.ThreadDumpAnalysisService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MainViewJudgeModeTest {

    @Test
    void restartingJudgeModeAlwaysRestoresTheDeadlockIncident() {
        var healthyResult = new ThreadDumpAnalysisService()
                .analyzeSample("Corrected lock order", "corrected.txt");

        assertThat(healthyResult.hasDeadlock()).isFalse();
        assertThat(MainView.requiresJudgeIncident(0, healthyResult)).isTrue();
    }

    @Test
    void laterJudgeStepsKeepTheActiveIncidentUnlessNoneIsLoaded() {
        var healthyResult = new ThreadDumpAnalysisService()
                .analyzeSample("Corrected lock order", "corrected.txt");

        assertThat(MainView.requiresJudgeIncident(1, healthyResult)).isFalse();
        assertThat(MainView.requiresJudgeIncident(1, null)).isTrue();
    }
}
