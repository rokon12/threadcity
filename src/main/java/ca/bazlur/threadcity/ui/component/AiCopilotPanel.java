package ca.bazlur.threadcity.ui.component;

import ca.bazlur.threadcity.ai.IncidentConversationTurn;
import ca.bazlur.threadcity.ai.IncidentExplanationRequest;
import ca.bazlur.threadcity.ai.IncidentExplanationService;
import ca.bazlur.threadcity.ai.AiUsageLimitException;
import ca.bazlur.threadcity.domain.AnalysisResult;
import ca.bazlur.threadcity.ui.support.AiEvidenceReference;
import ca.bazlur.threadcity.ui.support.AiEvidenceResolver;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.confirmdialog.ConfirmDialog;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.messages.MessageInput;
import com.vaadin.flow.component.messages.MessageList;
import com.vaadin.flow.component.messages.MessageListItem;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.progressbar.ProgressBar;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Owns the opt-in AI conversation, consent, and asynchronous provider calls.
 */
public final class AiCopilotPanel extends Div {

    private static final String DEFAULT_QUESTION =
            "Explain this incident, identify the strongest evidence, and propose a fix with verification steps.";

    private final IncidentExplanationService explanationService;
    private final Runnable revealCopilot;
    private final Consumer<String> errorNotifier;
    private final Consumer<AiEvidenceReference> evidenceNavigator;
    private final AtomicLong resultGeneration = new AtomicLong();
    private final AtomicLong requestGeneration = new AtomicLong();
    private final Button action = new Button("✦ Explain with AI");
    private final Button stop = new Button("Stop");
    private final Button regenerate = new Button("Regenerate");
    private final MessageList messages = new MessageList();
    private final MessageInput input = new MessageInput();
    private final ProgressBar progress = new ProgressBar();
    private final List<MessageListItem> conversation = new ArrayList<>();
    private final List<IncidentConversationTurn> conversationMemory = new ArrayList<>();
    private final Span memoryStatus = new Span();
    private final Span streamStatus = new Span();
    private final Div evidenceLinks = new Div();

    private AnalysisResult result;
    private IncidentExplanationRequest activeRequest;
    private MessageListItem activeResponseItem;
    private String lastQuestion;
    private boolean consentGranted;
    private boolean controlsEnabled = true;
    private boolean busy;

    public AiCopilotPanel(
            IncidentExplanationService explanationService,
            Runnable revealCopilot,
            Consumer<String> errorNotifier,
            Consumer<AiEvidenceReference> evidenceNavigator) {
        this.explanationService = explanationService;
        this.revealCopilot = revealCopilot;
        this.errorNotifier = errorNotifier;
        this.evidenceNavigator = evidenceNavigator;
        configureControls();
        buildLayout();
        resetConversation();
        refreshControlState();
    }

    public Button actionButton() {
        return action;
    }

    public void ask(String question) {
        request(question);
    }

    public void showResult(AnalysisResult result) {
        cancelActiveRequest(false);
        resultGeneration.incrementAndGet();
        this.result = result;
        busy = false;
        resetConversation();
        refreshControlState();
    }

    public void clear() {
        cancelActiveRequest(false);
        resultGeneration.incrementAndGet();
        result = null;
        busy = false;
        resetConversation();
        refreshControlState();
    }

    public void setControlsEnabled(boolean enabled) {
        controlsEnabled = enabled;
        refreshControlState();
    }

    private void configureControls() {
        action.addClickListener(event -> request(DEFAULT_QUESTION));
        action.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        action.addClassName("ai-action");
        action.setVisible(explanationService.isAvailable());

        messages.addClassName("copilot-messages");
        messages.setMarkdown(false);
        messages.setAnnounceMessages(true);

        input.addClassName("copilot-input");
        input.setWidthFull();
        input.getElement().setProperty(
                "placeholder",
                explanationService.isAvailable()
                        ? "Ask why a thread is blocked or how to verify the fix…"
                        : "Clone ThreadCity and configure your own API key to enable AI…");
        input.addSubmitListener(event -> request(event.getValue()));

        stop.addClickListener(event -> cancelActiveRequest(true));
        stop.addThemeVariants(ButtonVariant.LUMO_ERROR, ButtonVariant.LUMO_TERTIARY);
        stop.setVisible(false);

        regenerate.addClickListener(event -> regenerateLastAnswer());
        regenerate.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE);
        regenerate.setVisible(false);

        progress.setIndeterminate(true);
        progress.setVisible(false);
    }

    private void buildLayout() {
        Div disclosure = explanationService.isAvailable()
                ? configuredDisclosure()
                : localByokDisclosure();
        memoryStatus.getElement().getThemeList().add("badge contrast");
        memoryStatus.getElement().setAttribute("aria-live", "polite");
        streamStatus.addClassName("copilot-stream-status");
        streamStatus.getElement().setAttribute("aria-live", "polite");
        HorizontalLayout sessionBar = new HorizontalLayout(memoryStatus, streamStatus, regenerate, stop);
        sessionBar.addClassName("copilot-session-bar");
        sessionBar.setAlignItems(HorizontalLayout.Alignment.CENTER);
        evidenceLinks.addClassName("copilot-evidence-links");
        evidenceLinks.setVisible(false);
        add(new H2("AI incident copilot"), disclosure, sessionBar, progress, messages, evidenceLinks, input);
        addClassNames("panel", "copilot-panel");
    }

    private Div configuredDisclosure() {
        Div disclosure = new Div(new Span("Opt-in: questions and bounded derived evidence go to "
                + explanationService.providerName() + ". Raw thread dumps are never sent."));
        disclosure.addClassName("copilot-disclosure");
        return disclosure;
    }

    private Div localByokDisclosure() {
        Span message = new Span("AI is currently unavailable in this demo. ");
        Anchor source = new Anchor(
                "https://github.com/rokon12/threadcity",
                "Clone ThreadCity and use your own API key");
        source.setTarget("_blank");
        source.getElement().setAttribute("rel", "noopener noreferrer");
        Div disclosure = new Div(message, source);
        disclosure.addClassNames("copilot-disclosure", "copilot-byok");
        return disclosure;
    }

    private void request(String question) {
        if (busy) {
            errorNotifier.accept("The copilot is already answering. Stop that response before asking another question.");
            return;
        }
        if (result == null || !explanationService.isAvailable() || question == null || question.isBlank()) {
            errorNotifier.accept("AI explanations are not available for this deployment.");
            return;
        }
        if (consentGranted) {
            submit(question);
            return;
        }

        ConfirmDialog dialog = new ConfirmDialog();
        dialog.setHeader("Open the AI incident copilot?");
        dialog.setText("ThreadCity will send thread names, states, confirmed lock relationships, findings, "
                + "representative top stack frames, and your questions to "
                + explanationService.providerName() + " through LangChain4j. "
                + "The raw dump is not sent. This consent applies to the current analyzed snapshot.");
        dialog.setConfirmText("Open copilot");
        dialog.setCancelText("Cancel");
        dialog.setCancelable(true);
        dialog.addConfirmListener(event -> {
            consentGranted = true;
            submit(question);
        });
        dialog.open();
    }

    private void submit(String question) {
        String normalized = question.strip();
        revealCopilot.run();
        addMessage(normalized, "You", 1);
        generateExplanation(normalized, List.copyOf(conversationMemory));
    }

    private void generateExplanation(String question, List<IncidentConversationTurn> priorConversation) {
        AnalysisResult requestedResult = result;
        if (requestedResult == null) {
            return;
        }
        long generation = resultGeneration.get();
        long request = requestGeneration.incrementAndGet();
        UI ui = UI.getCurrent();
        AtomicBoolean firstChunk = new AtomicBoolean(true);
        activeResponseItem = addMessage("Connecting to the evidence stream…", "ThreadCity AI", 5);
        evidenceLinks.removeAll();
        evidenceLinks.setVisible(false);
        setBusy(true);

        try {
            activeRequest = explanationService.explainStreaming(
                    requestedResult,
                    priorConversation,
                    question,
                    partial -> streamPartial(generation, request, ui, firstChunk, partial),
                    explanation -> completeStream(generation, request, ui, requestedResult, question, explanation),
                    exception -> failStream(generation, request, ui, requestedResult, exception));
        } catch (RuntimeException exception) {
            failStream(generation, request, ui, requestedResult, exception);
        }
    }

    private void streamPartial(
            long generation,
            long request,
            UI ui,
            AtomicBoolean firstChunk,
            String partial) {
        if (isStale(generation, request, ui)) {
            return;
        }
        boolean first = firstChunk.getAndSet(false);
        ui.access(() -> {
            if (!isCurrent(generation, request)) {
                return;
            }
            if (first) {
                activeResponseItem.setText(partial);
                streamStatus.setText("Streaming live");
            } else {
                activeResponseItem.appendText(partial);
            }
        });
    }

    private void completeStream(
            long generation,
            long request,
            UI ui,
            AnalysisResult requestedResult,
            String question,
            String explanation) {
        if (isStale(generation, request, ui)) {
            return;
        }
        ui.access(() -> {
            if (!isCurrent(generation, request) || result != requestedResult) {
                return;
            }
            activeResponseItem.setText(explanation);
            conversationMemory.add(new IncidentConversationTurn(question, explanation));
            lastQuestion = question;
            renderEvidenceLinks(requestedResult, question, explanation);
            refreshMemoryStatus();
            activeRequest = null;
            activeResponseItem = null;
            setBusy(false);
        });
    }

    private void failStream(
            long generation,
            long request,
            UI ui,
            AnalysisResult requestedResult,
            Throwable failure) {
        if (isStale(generation, request, ui)) {
            return;
        }
        ui.access(() -> {
            if (!isCurrent(generation, request) || result != requestedResult) {
                return;
            }
            String message = userFacingFailure(failure);
            activeResponseItem.setText(message);
            activeRequest = null;
            activeResponseItem = null;
            setBusy(false);
            errorNotifier.accept(message);
        });
    }

    private static String userFacingFailure(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof AiUsageLimitException limitException) {
                return limitException.userMessage();
            }
            current = current.getCause();
        }
        return "The AI incident brief is temporarily unavailable. The deterministic analysis is unchanged.";
    }

    private boolean isStale(long generation, long request, UI ui) {
        return !isCurrent(generation, request) || !ui.isAttached();
    }

    private boolean isCurrent(long generation, long request) {
        return generation == resultGeneration.get() && request == requestGeneration.get();
    }

    private void setBusy(boolean busy) {
        this.busy = busy;
        action.setText(busy ? "Analyzing…" : "✦ Explain with AI");
        progress.setVisible(busy);
        stop.setVisible(busy);
        regenerate.setVisible(!busy && lastQuestion != null);
        streamStatus.setText(busy ? "Connecting…" : "Ready");
        refreshControlState();
    }

    private void refreshControlState() {
        boolean enabled = controlsEnabled && !busy && explanationService.isAvailable();
        action.setEnabled(enabled);
        input.setEnabled(enabled);
    }

    private MessageListItem addMessage(String text, String author, int colorIndex) {
        MessageListItem item = new MessageListItem(text, Instant.now(), author);
        item.setUserAbbreviation(author.equals("You") ? "YO" : "TC");
        item.setUserColorIndex(colorIndex);
        conversation.add(item);
        messages.setItems(conversation);
        return item;
    }

    private void resetConversation() {
        consentGranted = false;
        conversation.clear();
        conversationMemory.clear();
        lastQuestion = null;
        activeResponseItem = null;
        evidenceLinks.removeAll();
        evidenceLinks.setVisible(false);
        String introduction = explanationService.isAvailable()
                ? "Select Explain with AI or ask a question. I will answer from ThreadCity's deterministic evidence and clearly mark AI-generated guidance."
                : "This public demo intentionally makes no AI provider calls. Clone the project, set your own API key locally, and the same workspace becomes a conversational LangChain4j incident copilot. Every deterministic investigation tool remains available here.";
        addMessage(introduction, "ThreadCity", 5);
        refreshMemoryStatus();
        action.setText("✦ Explain with AI");
        progress.setVisible(false);
        stop.setVisible(false);
        regenerate.setVisible(false);
        streamStatus.setText(explanationService.isAvailable() ? "Ready" : "Local BYOK");
    }

    private void refreshMemoryStatus() {
        int turns = conversationMemory.size();
        memoryStatus.setText("Snapshot memory · " + turns + (turns == 1 ? " turn" : " turns"));
    }

    private void renderEvidenceLinks(AnalysisResult result, String question, String explanation) {
        List<AiEvidenceReference> references = AiEvidenceResolver.resolve(result, question, explanation);
        evidenceLinks.removeAll();
        if (references.isEmpty()) {
            evidenceLinks.setVisible(false);
            return;
        }
        Span label = new Span("Open deterministic evidence");
        label.addClassName("copilot-evidence-label");
        HorizontalLayout actions = new HorizontalLayout();
        actions.addClassName("copilot-evidence-actions");
        references.forEach(reference -> {
            Button link = new Button(reference.label(), event -> evidenceNavigator.accept(reference));
            link.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE);
            actions.add(link);
        });
        evidenceLinks.add(label, actions);
        evidenceLinks.setVisible(true);
    }

    private void regenerateLastAnswer() {
        if (lastQuestion == null || busy) {
            return;
        }
        String question = lastQuestion;
        if (!conversationMemory.isEmpty()
                && conversationMemory.getLast().question().equals(question)) {
            conversationMemory.removeLast();
            refreshMemoryStatus();
        }
        request(question);
    }

    private void cancelActiveRequest(boolean showStoppedMessage) {
        requestGeneration.incrementAndGet();
        if (activeRequest != null) {
            activeRequest.cancel();
            activeRequest = null;
        }
        if (showStoppedMessage && activeResponseItem != null) {
            String partial = activeResponseItem.getText();
            activeResponseItem.setText(
                    partial == null || partial.startsWith("Connecting")
                            ? "Generation stopped before a response arrived."
                            : partial + "\n\n[Generation stopped]");
        }
        activeResponseItem = null;
        if (busy) {
            setBusy(false);
        }
    }
}
