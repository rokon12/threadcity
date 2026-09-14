package ca.bazlur.threadcity.ai;

import ca.bazlur.threadcity.domain.AnalysisResult;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public interface IncidentExplanationService {

    boolean isAvailable();

    default String providerName() {
        return "AI provider";
    }

    default String explain(AnalysisResult result) {
        return explain(result, List.of(), "Explain this incident and recommend the safest next steps.");
    }

    default String explain(AnalysisResult result, String question) {
        return explain(result, List.of(), question);
    }

    String explain(AnalysisResult result, List<IncidentConversationTurn> conversation, String question);

    default IncidentExplanationRequest explainStreaming(
            AnalysisResult result,
            List<IncidentConversationTurn> conversation,
            String question,
            Consumer<String> onPartialResponse,
            Consumer<String> onComplete,
            Consumer<Throwable> onError) {
        Objects.requireNonNull(onPartialResponse, "onPartialResponse");
        Objects.requireNonNull(onComplete, "onComplete");
        Objects.requireNonNull(onError, "onError");
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicReference<Thread> worker = new AtomicReference<>();
        IncidentExplanationRequest request = new IncidentExplanationRequest() {
            @Override
            public void cancel() {
                cancelled.set(true);
                Thread thread = worker.get();
                if (thread != null) {
                    thread.interrupt();
                }
            }

            @Override
            public boolean isCancelled() {
                return cancelled.get();
            }
        };
        Thread thread = Thread.ofVirtual().name("threadcity-ai-fallback").start(() -> {
            try {
                String explanation = explain(result, conversation, question);
                if (!cancelled.get()) {
                    onPartialResponse.accept(explanation);
                    onComplete.accept(explanation);
                }
            } catch (RuntimeException exception) {
                if (!cancelled.get()) {
                    onError.accept(exception);
                }
            }
        });
        worker.set(thread);
        return request;
    }
}
