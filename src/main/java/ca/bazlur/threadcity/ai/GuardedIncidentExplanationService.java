package ca.bazlur.threadcity.ai;

import ca.bazlur.threadcity.domain.AnalysisResult;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Keeps every provider call behind the same usage and concurrency controls.
 */
final class GuardedIncidentExplanationService implements IncidentExplanationService {

    private final IncidentExplanationService delegate;
    private final AiUsageGuard usageGuard;

    GuardedIncidentExplanationService(IncidentExplanationService delegate, AiUsageGuard usageGuard) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.usageGuard = Objects.requireNonNull(usageGuard, "usageGuard");
    }

    @Override
    public boolean isAvailable() {
        return delegate.isAvailable() && usageGuard.isOperational();
    }

    @Override
    public String providerName() {
        return delegate.providerName();
    }

    @Override
    public String explain(
            AnalysisResult result,
            List<IncidentConversationTurn> conversation,
            String question) {
        try (AiUsageGuard.Permit ignored = usageGuard.acquire()) {
            return delegate.explain(result, conversation, question);
        }
    }

    @Override
    public IncidentExplanationRequest explainStreaming(
            AnalysisResult result,
            List<IncidentConversationTurn> conversation,
            String question,
            Consumer<String> onPartialResponse,
            Consumer<String> onComplete,
            Consumer<Throwable> onError) {
        AiUsageGuard.Permit permit = usageGuard.acquire();
        try {
            IncidentExplanationRequest request = delegate.explainStreaming(
                    result,
                    conversation,
                    question,
                    onPartialResponse,
                    answer -> {
                        permit.close();
                        onComplete.accept(answer);
                    },
                    failure -> {
                        permit.close();
                        onError.accept(failure);
                    });
            return new GuardedRequest(request, permit);
        } catch (RuntimeException exception) {
            permit.close();
            throw exception;
        }
    }

    private record GuardedRequest(IncidentExplanationRequest delegate, AiUsageGuard.Permit permit)
            implements IncidentExplanationRequest {

        private GuardedRequest {
            Objects.requireNonNull(delegate, "delegate");
            Objects.requireNonNull(permit, "permit");
        }

        @Override
        public void cancel() {
            try {
                delegate.cancel();
            } finally {
                permit.close();
            }
        }

        @Override
        public boolean isCancelled() {
            return delegate.isCancelled();
        }
    }
}
