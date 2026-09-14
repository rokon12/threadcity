package ca.bazlur.threadcity.ai;

import ca.bazlur.threadcity.domain.AnalysisResult;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatModelStreamingEvent;
import dev.langchain4j.model.chat.response.PartialResponse;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

final class LangChainIncidentExplanationService implements IncidentExplanationService {

    static final int MAX_RESPONSE_CHARS = 5_000;

    private final ChatModel chatModel;
    private final StreamingChatModel streamingChatModel;
    private final String providerName;

    LangChainIncidentExplanationService(ChatModel chatModel) {
        this(chatModel, null, "AI provider");
    }

    LangChainIncidentExplanationService(ChatModel chatModel, StreamingChatModel streamingChatModel) {
        this(chatModel, streamingChatModel, "AI provider");
    }

    LangChainIncidentExplanationService(
            ChatModel chatModel,
            StreamingChatModel streamingChatModel,
            String providerName) {
        this.chatModel = Objects.requireNonNull(chatModel, "chatModel");
        this.streamingChatModel = streamingChatModel;
        this.providerName = Objects.requireNonNull(providerName, "providerName");
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public String providerName() {
        return providerName;
    }

    @Override
    public String explain(
            AnalysisResult result,
            List<IncidentConversationTurn> conversation,
            String question) {
        List<ChatMessage> messages = IncidentPromptBuilder.buildMessages(result, conversation, question);
        return normalize(chatModel.chat(messages).aiMessage().text());
    }

    @Override
    public IncidentExplanationRequest explainStreaming(
            AnalysisResult result,
            List<IncidentConversationTurn> conversation,
            String question,
            Consumer<String> onPartialResponse,
            Consumer<String> onComplete,
            Consumer<Throwable> onError) {
        if (streamingChatModel == null) {
            return IncidentExplanationService.super.explainStreaming(
                    result, conversation, question, onPartialResponse, onComplete, onError);
        }
        List<ChatMessage> messages = IncidentPromptBuilder.buildMessages(result, conversation, question);
        ResponseSubscriber subscriber = new ResponseSubscriber(onPartialResponse, onComplete, onError);
        streamingChatModel.chat(messages).subscribe(subscriber);
        return subscriber;
    }

    private static String normalize(String response) {
        if (response == null || response.isBlank()) {
            throw new IllegalStateException("The AI provider returned an empty explanation");
        }
        String normalized = response.strip();
        return normalized.length() <= MAX_RESPONSE_CHARS
                ? normalized
                : normalized.substring(0, MAX_RESPONSE_CHARS - 1) + "…";
    }

    private static final class ResponseSubscriber
            implements Flow.Subscriber<ChatModelStreamingEvent>, IncidentExplanationRequest {

        private final Consumer<String> onPartialResponse;
        private final Consumer<String> onComplete;
        private final Consumer<Throwable> onError;
        private final AtomicReference<Flow.Subscription> subscription = new AtomicReference<>();
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicBoolean terminal = new AtomicBoolean();
        private final StringBuilder response = new StringBuilder();

        private ResponseSubscriber(
                Consumer<String> onPartialResponse,
                Consumer<String> onComplete,
                Consumer<Throwable> onError) {
            this.onPartialResponse = Objects.requireNonNull(onPartialResponse, "onPartialResponse");
            this.onComplete = Objects.requireNonNull(onComplete, "onComplete");
            this.onError = Objects.requireNonNull(onError, "onError");
        }

        @Override
        public void onSubscribe(Flow.Subscription newSubscription) {
            if (!subscription.compareAndSet(null, newSubscription) || cancelled.get()) {
                newSubscription.cancel();
                return;
            }
            newSubscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(ChatModelStreamingEvent event) {
            if (!(event instanceof PartialResponse partialResponse)) {
                return;
            }
            String text = partialResponse.text();
            if (cancelled.get() || terminal.get() || text == null || text.isEmpty()) {
                return;
            }
            int remaining = MAX_RESPONSE_CHARS - response.length();
            if (remaining <= 1) {
                if (remaining == 1) {
                    response.append('…');
                    onPartialResponse.accept("…");
                }
                finishAtLimit();
                return;
            }
            String accepted = text.length() < remaining ? text : text.substring(0, remaining - 1) + "…";
            response.append(accepted);
            onPartialResponse.accept(accepted);
            if (text.length() >= remaining) {
                finishAtLimit();
            }
        }

        @Override
        public void onError(Throwable throwable) {
            if (!cancelled.get() && terminal.compareAndSet(false, true)) {
                onError.accept(throwable);
            }
        }

        @Override
        public void onComplete() {
            completeResponse();
        }

        @Override
        public void cancel() {
            if (cancelled.compareAndSet(false, true)) {
                Flow.Subscription current = subscription.get();
                if (current != null) {
                    current.cancel();
                }
            }
        }

        @Override
        public boolean isCancelled() {
            return cancelled.get();
        }

        private void finishAtLimit() {
            Flow.Subscription current = subscription.get();
            if (current != null) {
                current.cancel();
            }
            completeResponse();
        }

        private void completeResponse() {
            if (cancelled.get() || !terminal.compareAndSet(false, true)) {
                return;
            }
            try {
                onComplete.accept(normalize(response.toString()));
            } catch (RuntimeException exception) {
                onError.accept(exception);
            }
        }
    }
}
