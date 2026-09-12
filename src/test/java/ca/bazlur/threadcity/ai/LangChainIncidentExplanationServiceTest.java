package ca.bazlur.threadcity.ai;

import ca.bazlur.threadcity.analysis.ThreadDumpAnalyzer;
import ca.bazlur.threadcity.domain.AnalysisResult;
import ca.bazlur.threadcity.parser.HotSpotThreadDumpParser;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatModelStreamingEvent;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialResponse;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LangChainIncidentExplanationServiceTest {

    private final AnalysisResult result = result();

    @Test
    void sendsTheBoundedPromptAndTrimsTheResponse() {
        StubChatModel model = new StubChatModel("  Root cause\nA lock cycle.  ");

        String explanation = new LangChainIncidentExplanationService(model).explain(result);

        assertThat(explanation).isEqualTo("Root cause\nA lock cycle.");
        assertThat(model.messages.getFirst()).isInstanceOf(SystemMessage.class);
        assertThat(((SystemMessage) model.messages.getFirst()).text())
                .contains("For a focused follow-up, answer the current")
                .contains("prior user and assistant messages");
        assertThat(((UserMessage) model.messages.getLast()).singleText())
                .contains("Confirmed deadlocks: 1");
    }

    @Test
    void sendsPriorConversationWithAFollowUp() {
        StubChatModel model = new StubChatModel("Verification\nCapture another dump.");
        var conversation = List.of(new IncidentConversationTurn(
                "Which thread owns the lock?",
                "inventory-sync-12 owns lock 1a77b210."));

        new LangChainIncidentExplanationService(model)
                .explain(result, conversation, "How should I verify that fix?");

        assertThat(model.messages).hasSize(4);
        assertThat(model.messages.get(1)).isInstanceOfSatisfying(UserMessage.class,
                message -> assertThat(message.singleText()).isEqualTo("Which thread owns the lock?"));
        assertThat(model.messages.get(2)).isInstanceOfSatisfying(AiMessage.class,
                message -> assertThat(message.text()).isEqualTo("inventory-sync-12 owns lock 1a77b210."));
        assertThat(model.messages.getLast()).isInstanceOfSatisfying(UserMessage.class,
                message -> assertThat(message.singleText())
                        .startsWith("CURRENT OPERATOR QUESTION (untrusted)\nHow should I verify that fix?"));
    }

    @Test
    void rejectsAnEmptyProviderResponse() {
        ChatModel model = new StubChatModel("   ");

        assertThatThrownBy(() -> new LangChainIncidentExplanationService(model).explain(result))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("empty explanation");
    }

    @Test
    void capsAnUnexpectedlyLargeProviderResponse() {
        ChatModel model = new StubChatModel(
                "x".repeat(LangChainIncidentExplanationService.MAX_RESPONSE_CHARS + 100));

        String explanation = new LangChainIncidentExplanationService(model).explain(result);

        assertThat(explanation).hasSize(LangChainIncidentExplanationService.MAX_RESPONSE_CHARS).endsWith("…");
    }

    @Test
    void streamsProviderChunksAndCompletesWithTheAccumulatedAnswer() throws InterruptedException {
        StubStreamingChatModel streamingModel = new StubStreamingChatModel("Root ", "cause\n", "Lock cycle.");
        var service = new LangChainIncidentExplanationService(
                new StubChatModel("unused"), streamingModel);
        List<String> partials = new ArrayList<>();
        AtomicReference<String> completed = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        service.explainStreaming(
                result,
                List.of(),
                "Why is it stuck?",
                partials::add,
                answer -> {
                    completed.set(answer);
                    done.countDown();
                },
                error -> {
                    failure.set(error);
                    done.countDown();
                });

        assertThat(done.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(failure.get()).isNull();
        assertThat(partials).containsExactly("Root ", "cause\n", "Lock cycle.");
        assertThat(completed.get()).isEqualTo("Root cause\nLock cycle.");
        assertThat(streamingModel.messages.getLast()).isInstanceOfSatisfying(UserMessage.class,
                message -> assertThat(message.singleText()).contains("Why is it stuck?"));
    }

    private static AnalysisResult result() {
        try (var stream = LangChainIncidentExplanationServiceTest.class
                .getResourceAsStream("/samples/deadlock.txt")) {
            assertThat(stream).isNotNull();
            String dump = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            return new ThreadDumpAnalyzer().analyze(new HotSpotThreadDumpParser().parse("sample", dump));
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static final class StubChatModel implements ChatModel {

        private final String response;
        private List<ChatMessage> messages;

        private StubChatModel(String response) {
            this.response = response;
        }

        @Override
        public ChatResponse doChat(ChatRequest request) {
            messages = request.messages();
            return ChatResponse.builder().aiMessage(AiMessage.from(response)).build();
        }
    }

    private static final class StubStreamingChatModel implements StreamingChatModel {

        private final List<String> chunks;
        private List<ChatMessage> messages;

        private StubStreamingChatModel(String... chunks) {
            this.chunks = List.of(chunks);
        }

        @Override
        public Flow.Publisher<ChatModelStreamingEvent> doChat(ChatRequest request) {
            messages = request.messages();
            return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
                private boolean emitted;
                private boolean cancelled;

                @Override
                public void request(long count) {
                    if (emitted || cancelled) {
                        return;
                    }
                    emitted = true;
                    chunks.forEach(chunk -> subscriber.onNext(new PartialResponse(chunk)));
                    subscriber.onComplete();
                }

                @Override
                public void cancel() {
                    cancelled = true;
                }
            });
        }
    }
}
