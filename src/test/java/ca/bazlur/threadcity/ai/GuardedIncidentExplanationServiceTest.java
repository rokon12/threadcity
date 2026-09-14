package ca.bazlur.threadcity.ai;

import ca.bazlur.threadcity.domain.AnalysisResult;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GuardedIncidentExplanationServiceTest {

    @Test
    void holdsConcurrencyUntilAStreamCompletes() {
        ControlledStreamingService provider = new ControlledStreamingService();
        IncidentExplanationService service = new GuardedIncidentExplanationService(provider, guard(1));

        service.explainStreaming(null, List.of(), "first", ignored -> { }, ignored -> { }, ignored -> { });

        assertThatThrownBy(() -> service.explainStreaming(
                null, List.of(), "second", ignored -> { }, ignored -> { }, ignored -> { }))
                .isInstanceOf(AiUsageLimitException.class)
                .hasMessageContaining("Concurrent");

        provider.complete("answer");
        service.explainStreaming(null, List.of(), "third", ignored -> { }, ignored -> { }, ignored -> { });
    }

    @Test
    void cancellationReleasesConcurrencyExactlyOnce() {
        ControlledStreamingService provider = new ControlledStreamingService();
        IncidentExplanationService service = new GuardedIncidentExplanationService(provider, guard(1));
        IncidentExplanationRequest request = service.explainStreaming(
                null, List.of(), "first", ignored -> { }, ignored -> { }, ignored -> { });

        request.cancel();
        request.cancel();

        assertThat(request.isCancelled()).isTrue();
        service.explainStreaming(null, List.of(), "second", ignored -> { }, ignored -> { }, ignored -> { });
    }

    private static AiUsageGuard guard(int concurrency) {
        return new AiUsageGuard(
                Clock.fixed(Instant.parse("2026-09-14T16:00:00Z"), ZoneOffset.UTC),
                new AiClientIdentityResolver(),
                true,
                10,
                10,
                10,
                concurrency,
                null,
                null);
    }

    private static final class ControlledStreamingService implements IncidentExplanationService {

        private final AtomicReference<Consumer<String>> completion = new AtomicReference<>();
        private boolean cancelled;

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public String explain(
                AnalysisResult result,
                List<IncidentConversationTurn> conversation,
                String question) {
            return "unused";
        }

        @Override
        public IncidentExplanationRequest explainStreaming(
                AnalysisResult result,
                List<IncidentConversationTurn> conversation,
                String question,
                Consumer<String> onPartialResponse,
                Consumer<String> onComplete,
                Consumer<Throwable> onError) {
            cancelled = false;
            completion.set(onComplete);
            return new IncidentExplanationRequest() {
                @Override
                public void cancel() {
                    cancelled = true;
                }

                @Override
                public boolean isCancelled() {
                    return cancelled;
                }
            };
        }

        private void complete(String answer) {
            completion.getAndSet(null).accept(answer);
        }
    }
}
