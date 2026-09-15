package ca.bazlur.threadcity.ui.component;

import ca.bazlur.threadcity.ai.IncidentConversationTurn;
import ca.bazlur.threadcity.ai.IncidentExplanationService;
import ca.bazlur.threadcity.domain.AnalysisResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AiCopilotPanelTest {

    @Test
    void keepsAiActionVisibleButDisabledWithoutAProviderKey() {
        AiCopilotPanel panel = new AiCopilotPanel(
                new UnavailableService(),
                () -> { },
                ignored -> { },
                ignored -> { });

        assertThat(panel.actionButton().isVisible()).isTrue();
        assertThat(panel.actionButton().isEnabled()).isFalse();
        assertThat(panel.actionButton().getElement().getAttribute("title"))
                .isEqualTo("Set OPENAI_API_KEY to enable the AI copilot");
    }

    private static final class UnavailableService implements IncidentExplanationService {

        @Override
        public boolean isAvailable() {
            return false;
        }

        @Override
        public String explain(
                AnalysisResult result,
                List<IncidentConversationTurn> conversation,
                String question) {
            throw new IllegalStateException("AI is unavailable");
        }
    }
}
