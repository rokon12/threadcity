package ca.bazlur.threadcity.ai;

import ca.bazlur.threadcity.domain.AnalysisResult;

import java.util.List;

final class UnavailableIncidentExplanationService implements IncidentExplanationService {

    @Override
    public boolean isAvailable() {
        return false;
    }

    @Override
    public String explain(
            AnalysisResult result,
            List<IncidentConversationTurn> conversation,
            String question) {
        throw new IllegalStateException("AI incident explanations are not configured");
    }
}
