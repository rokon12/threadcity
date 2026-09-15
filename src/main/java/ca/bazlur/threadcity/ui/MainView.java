package ca.bazlur.threadcity.ui;

import ca.bazlur.threadcity.ai.IncidentExplanationService;
import ca.bazlur.threadcity.application.EvidenceTaskExecutor;
import ca.bazlur.threadcity.application.ThreadDumpAnalysisService;
import ca.bazlur.threadcity.application.JfrAnalysisService;
import ca.bazlur.threadcity.application.IncidentBundleService;
import ca.bazlur.threadcity.application.IncidentReportService;
import ca.bazlur.threadcity.domain.AnalysisResult;
import ca.bazlur.threadcity.domain.Finding;
import ca.bazlur.threadcity.domain.JavaThread;
import ca.bazlur.threadcity.domain.IncidentBundle;
import ca.bazlur.threadcity.domain.JfrAnalysis;
import ca.bazlur.threadcity.domain.ThreadMetadata;
import ca.bazlur.threadcity.domain.ThreadState;
import ca.bazlur.threadcity.domain.WaitEdge;
import ca.bazlur.threadcity.parser.ThreadDumpUploadValidator;
import ca.bazlur.threadcity.ui.component.AiCopilotPanel;
import ca.bazlur.threadcity.ui.component.BlockerLeaderboard;
import ca.bazlur.threadcity.ui.component.EvidenceUploadControls;
import ca.bazlur.threadcity.ui.component.IncidentComparisonPanel;
import ca.bazlur.threadcity.ui.component.IncidentTimeMachine;
import ca.bazlur.threadcity.ui.component.IncidentPatternPanel;
import ca.bazlur.threadcity.ui.component.LockTrafficMap;
import ca.bazlur.threadcity.ui.component.MultiDumpComparisonPanel;
import ca.bazlur.threadcity.ui.component.ParserConfidencePanel;
import ca.bazlur.threadcity.ui.component.JfrTimelinePanel;
import ca.bazlur.threadcity.ui.component.JudgeModeCoach;
import ca.bazlur.threadcity.ui.component.SynchronizerObservatory;
import ca.bazlur.threadcity.ui.component.StackCohortExplorer;
import ca.bazlur.threadcity.ui.component.ThreadEvidencePanel;
import ca.bazlur.threadcity.ui.component.WorkbenchNavigator;
import ca.bazlur.threadcity.ui.support.IncidentNarrative;
import ca.bazlur.threadcity.ui.support.AiEvidenceReference;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.tabs.Tab;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.streams.DownloadHandler;
import com.vaadin.flow.server.streams.DownloadResponse;
import com.vaadin.flow.server.streams.UploadMetadata;
import com.vaadin.flow.shared.Registration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

@Route(value = "", layout = MainLayout.class)
@PageTitle("ThreadCity — JVM traffic control")
public class MainView extends Div {

    private static final Logger LOGGER = LoggerFactory.getLogger(MainView.class);

    private static final List<String> FIX_STEPS = List.of(
            "Payment lock acquired",
            "Inventory lock requested in the same order",
            "Second operation queues behind that ordering",
            "First operation completes and releases both locks",
            "Second operation completes",
            "Traffic flowing normally");

    private final ThreadDumpAnalysisService analysisService;
    private final IncidentBundleService bundleService;
    private final IncidentReportService reportService;
    private final JfrAnalysisService jfrAnalysisService;
    private final EvidenceTaskExecutor taskExecutor;
    private final boolean aiAvailable;
    private final ThreadDumpUploadValidator uploadValidator = new ThreadDumpUploadValidator();
    private final AtomicLong replayGeneration = new AtomicLong();
    private final AtomicLong analysisGeneration = new AtomicLong();

    private final Div hero = new Div();
    private final Div analysisSection = new Div();
    private final HorizontalLayout metrics = new HorizontalLayout();
    private final Div incidentStatus = new Div();
    private final Div replayTimeline = new Div();
    private final List<Div> replaySteps = new ArrayList<>();
    private final Div findings = new Div();

    private final Div overviewPage = new Div();
    private final Div timelinePage = new Div();
    private final Div threadsPage = new Div();
    private final Div synchronizersPage = new Div();
    private final Div cohortsPage = new Div();
    private final Div jfrPage = new Div();
    private final Div comparePage = new Div();
    private final Div copilotPage = new Div();
    private final Tab overviewTab = tab(VaadinIcon.MAP_MARKER, "Incident map");
    private final Tab timelineTab = tab(VaadinIcon.TIME_BACKWARD, "Time machine");
    private final Tab threadsTab = tab(VaadinIcon.TABLE, "Threads & evidence");
    private final Tab synchronizersTab = tab(VaadinIcon.LOCK, "Synchronizers");
    private final Tab cohortsTab = tab(VaadinIcon.CLUSTER, "Stack cohorts");
    private final Tab jfrTab = tab(VaadinIcon.CLOCK, "JFR timeline");
    private final Tab compareTab = tab(VaadinIcon.SPLIT, "Compare fix");
    private final Tab copilotTab = tab(VaadinIcon.CHAT, "AI copilot");
    private final WorkbenchNavigator workbench = new WorkbenchNavigator(List.of(
            new WorkbenchNavigator.Page(overviewTab, overviewPage),
            new WorkbenchNavigator.Page(timelineTab, timelinePage),
            new WorkbenchNavigator.Page(threadsTab, threadsPage),
            new WorkbenchNavigator.Page(synchronizersTab, synchronizersPage),
            new WorkbenchNavigator.Page(cohortsTab, cohortsPage),
            new WorkbenchNavigator.Page(jfrTab, jfrPage),
            new WorkbenchNavigator.Page(compareTab, comparePage),
            new WorkbenchNavigator.Page(copilotTab, copilotPage)));

    private final Button replayButton = new Button("Replay a production deadlock");
    private final Button fixButton = new Button("Replay with the fix");
    private final Button clearButton = new Button("Clear analysis");
    private final Button judgeModeButton = new Button("Launch Judge Mode", VaadinIcon.STAR.create());
    private final Button aiCopilotButton = new Button("✦ AI incident copilot");
    private final EvidenceUploadControls uploadControls;
    private final Anchor reportDownload;
    private final LockTrafficMap trafficMap;
    private final BlockerLeaderboard blockerLeaderboard;
    private final IncidentTimeMachine timeMachine;
    private final ThreadEvidencePanel evidencePanel;
    private final SynchronizerObservatory synchronizerObservatory;
    private final StackCohortExplorer stackCohortExplorer;
    private final ParserConfidencePanel parserConfidencePanel;
    private final IncidentPatternPanel incidentPatternPanel;
    private final JfrTimelinePanel jfrTimelinePanel;
    private final IncidentComparisonPanel comparisonPanel;
    private final MultiDumpComparisonPanel multiDumpComparisonPanel;
    private final AiCopilotPanel copilotPanel;
    private final JudgeModeCoach judgeModeCoach;

    private AnalysisResult currentResult;
    private Registration resizeRegistration;
    private Future<?> activeAnalysis;

    public MainView(
            IncidentExplanationService incidentExplanationService,
            ThreadDumpAnalysisService analysisService,
            JfrAnalysisService jfrAnalysisService,
            IncidentBundleService bundleService,
            IncidentReportService reportService,
            EvidenceTaskExecutor taskExecutor) {
        this.analysisService = analysisService;
        this.jfrAnalysisService = jfrAnalysisService;
        this.bundleService = bundleService;
        this.reportService = reportService;
        this.taskExecutor = taskExecutor;
        aiAvailable = incidentExplanationService.isAvailable();
        evidencePanel = new ThreadEvidencePanel(
                this::showSuccess, aiAvailable ? this::askAiAboutThread : null);
        trafficMap = new LockTrafficMap(
                "traffic-map", false, this::inspectThread, aiAvailable ? this::askAiAboutWait : null);
        blockerLeaderboard = new BlockerLeaderboard(this::inspectThread);
        synchronizerObservatory = new SynchronizerObservatory(this::inspectThread, this::inspectLock);
        stackCohortExplorer = new StackCohortExplorer(this::inspectThread);
        parserConfidencePanel = new ParserConfidencePanel(this::showSuccess);
        incidentPatternPanel = new IncidentPatternPanel(this::inspectThread);
        jfrTimelinePanel = new JfrTimelinePanel(
                jfrAnalysisService, taskExecutor, this::inspectThread, this::showSuccess, this::showError);
        timeMachine = new IncidentTimeMachine(analysisService, this::inspectThread);
        comparisonPanel = new IncidentComparisonPanel(analysisService);
        multiDumpComparisonPanel = new MultiDumpComparisonPanel(analysisService, this::showSuccess, this::showError);
        copilotPanel = new AiCopilotPanel(
                incidentExplanationService,
                () -> selectWorkbenchPage(copilotTab),
                this::showError,
                this::navigateAiEvidence);
        uploadControls = new EvidenceUploadControls(this::handleUpload, this::handleBundleUpload, this::showError);
        reportDownload = createReportDownload();
        judgeModeCoach = new JudgeModeCoach(this::navigateJudgeMode);

        configureActions();
        addClassName("app-shell");
        add(buildHero(), buildAnalysisSection(), buildFooter(), judgeModeCoach);
        registerLifecycleListeners();
    }

    private Component buildFooter() {
        Span message = new Span("Built with Vaadin Flow for the Vaadin community");
        Anchor source = new Anchor("https://github.com/rokon12/threadcity", "View source on GitHub");
        source.setTarget("_blank");
        source.getElement().setAttribute("rel", "noopener noreferrer");
        source.getElement().setAttribute("aria-label", "View ThreadCity source code on GitHub (opens in a new tab)");

        HorizontalLayout footer = new HorizontalLayout(message, source);
        footer.addClassName("app-footer");
        footer.setAlignItems(HorizontalLayout.Alignment.CENTER);
        footer.setJustifyContentMode(HorizontalLayout.JustifyContentMode.BETWEEN);
        footer.setWidthFull();
        return footer;
    }

    private void registerLifecycleListeners() {
        addAttachListener(event -> {
            UI ui = event.getUI();
            ui.getPage().getExtendedClientDetails().refresh(
                    details -> evidencePanel.updateResponsiveMode(details.getWindowInnerWidth()));
            resizeRegistration = ui.getPage().addBrowserWindowResizeListener(
                    resize -> evidencePanel.updateResponsiveMode(resize.getWidth()));
        });
        addDetachListener(event -> {
            cancelActiveWork();
            if (resizeRegistration != null) {
                resizeRegistration.remove();
                resizeRegistration = null;
            }
        });
    }

    private Anchor createReportDownload() {
        Anchor download = new Anchor(DownloadHandler.fromInputStream(event -> {
            if (currentResult == null) {
                return DownloadResponse.error(404, "Analyze an incident before exporting a dossier");
            }
            byte[] report = reportService.create(currentResult, jfrTimelinePanel.currentAnalysis());
            return new DownloadResponse(
                    new ByteArrayInputStream(report),
                    "threadcity-incident-dossier.html",
                    "text/html; charset=UTF-8",
                    report.length);
        }), "Download incident dossier");
        download.setDownload(true);
        download.setEnabled(false);
        download.addClassName("report-download");
        return download;
    }

    private void configureActions() {
        replayButton.addClickListener(event -> analyzeBuiltInIncident());
        replayButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY, ButtonVariant.LUMO_LARGE);
        replayButton.addClassName("hero-action");

        fixButton.addClickListener(event -> replayCorrectedIncident());
        fixButton.addThemeVariants(ButtonVariant.LUMO_CONTRAST, ButtonVariant.LUMO_LARGE);

        clearButton.addClickListener(event -> clearAnalysis());
        clearButton.addThemeVariants(ButtonVariant.LUMO_TERTIARY);

        judgeModeButton.addClickListener(event -> judgeModeCoach.open());
        judgeModeButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY, ButtonVariant.LUMO_LARGE);
        judgeModeButton.addClassName("judge-mode-button");

        aiCopilotButton.addClickListener(event -> openAiCopilot());
        aiCopilotButton.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_LARGE);
        aiCopilotButton.addClassName("hero-ai-button");
        aiCopilotButton.setEnabled(aiAvailable);
        aiCopilotButton.getElement().setAttribute(
                "title",
                aiAvailable
                        ? "Open the AI incident copilot"
                        : "Set OPENAI_API_KEY to enable this feature");
    }

    private void openAiCopilot() {
        if (!aiAvailable) {
            return;
        }
        if (currentResult == null) {
            render(analysisService.analyzeSample("AI copilot demo · checkout deadlock", "deadlock.txt"));
            revealAnalysis();
        }
        selectWorkbenchPage(copilotTab);
    }

    private Component buildHero() {
        Span eyebrow = new Span("JVM TRAFFIC CONTROL");
        eyebrow.addClassName("eyebrow");
        H1 title = new H1("See exactly who is blocking whom.");
        Paragraph description = new Paragraph(
                "Turn an unreadable Java thread dump into an interactive map of lock ownership, circular waits, and repeated work.");
        description.addClassName("hero-copy");

        HorizontalLayout actions = new HorizontalLayout(
                judgeModeButton, replayButton, fixButton, aiCopilotButton);
        actions.addClassName("hero-actions");
        actions.setPadding(false);
        Span hint = new Span("No sign-in · Nothing persisted · Deterministic Java core · AI is opt-in");
        hint.addClassName("hero-hint");

        VerticalLayout content = new VerticalLayout(
                eyebrow, title, description, actions, hint, uploadControls.dumpCard(), uploadControls.bundleCard());
        content.setPadding(false);
        content.setSpacing(false);
        content.addClassName("hero-content");

        Div signal = new Div();
        signal.addClassName("signal-art");
        signal.add(new Div("RUNNABLE"), new Div("WAITING"), new Div("BLOCKED"));
        Span signalCaption = new Span("LIVE LOCK TOPOLOGY");
        signalCaption.addClassName("signal-caption");
        signal.add(signalCaption);

        hero.add(content, signal);
        hero.addClassName("hero");
        return hero;
    }

    private Component buildAnalysisSection() {
        analysisSection.addClassName("analysis-section");
        analysisSection.setVisible(false);
        incidentStatus.addClassName("incident-status");
        replayTimeline.addClassName("replay-timeline");
        replayTimeline.setVisible(false);
        metrics.addClassName("metrics");
        metrics.setWidthFull();
        findings.addClassName("findings-panel");

        overviewPage.add(metrics, trafficMap, blockerLeaderboard, incidentPatternPanel, findings);
        timelinePage.add(timeMachine, replayTimeline);
        threadsPage.add(evidencePanel, parserConfidencePanel);
        synchronizersPage.add(synchronizerObservatory);
        cohortsPage.add(stackCohortExplorer);
        jfrPage.add(jfrTimelinePanel);
        comparePage.add(multiDumpComparisonPanel, comparisonPanel);
        copilotPage.add(copilotPanel);
        HorizontalLayout footerActions = new HorizontalLayout(clearButton);
        footerActions.addClassName("analysis-actions");
        analysisSection.add(incidentStatus, workbench, footerActions);
        return analysisSection;
    }

    private void selectWorkbenchPage(Tab tab) {
        workbench.select(tab);
    }

    private void handleUpload(UploadMetadata metadata, byte[] bytes) {
        cancelActiveWork();
        uploadControls.clearDump();
        runEvidenceTask(() -> {
            ThreadDumpUploadValidator.ValidatedUpload validated = uploadValidator.validate(
                    metadata.fileName(), metadata.contentType(), bytes);
            return analysisService.analyze(validated.sourceName(), validated.content());
        }, result -> {
            replayTimeline.setVisible(false);
            render(result);
            revealAnalysis();
            selectWorkbenchPage(overviewTab);
            showSuccess(result.snapshot().threads().size() + " threads analyzed. The original upload was not retained.");
        }, exception -> exception instanceof IllegalArgumentException
                ? exception.getMessage()
                : "ThreadCity could not analyze that file. Its content was not retained.");
    }

    private void handleBundleUpload(UploadMetadata metadata, byte[] bytes) {
        cancelActiveWork();
        uploadControls.clearBundle();
        runEvidenceTask(() -> {
            IncidentBundle bundle = bundleService.read(metadata.fileName(), bytes);
            List<AnalysisResult> snapshots = bundle.threadDumps().stream()
                    .map(dump -> analysisService.analyze(dump.sourceName(), dump.content()))
                    .toList();
            AnalysisResult latest = bundle.allThreads()
                    .map(dump -> analysisService.analyzeJava25(
                            dump.sourceName(), dump.content().getBytes(StandardCharsets.UTF_8)))
                    .orElseGet(snapshots::getLast);
            Optional<JfrAnalysis> jfr = bundle.recording()
                    .map(recording -> jfrAnalysisService.analyze("recording.jfr", recording));
            return new BundleAnalysis(snapshots, latest, jfr, bundle.allThreads().isPresent());
        }, bundle -> {
            jfrTimelinePanel.clear();
            multiDumpComparisonPanel.setSnapshots(bundle.snapshots());
            AnalysisResult latest = bundle.primary();
            render(latest);
            bundle.jfr().ifPresent(jfrTimelinePanel::showAnalysis);
            replayTimeline.setVisible(false);
            revealAnalysis();
            selectWorkbenchPage(overviewTab);
            long virtualThreads = latest.snapshot().threads().stream()
                    .filter(thread -> thread.metadata().kind() == ThreadMetadata.ThreadKind.VIRTUAL)
                    .count();
            String allThreads = bundle.hasAllThreads()
                    ? ", Java 25 all-thread evidence (" + virtualThreads + " virtual)"
                    : "";
            String jfr = bundle.jfr().isPresent() ? ", plus JFR" : "";
            showSuccess("Incident window opened: " + bundle.snapshots().size() + " chronological dumps"
                    + allThreads + jfr + ". The original bundle was not retained.");
        }, exception -> exception instanceof IllegalArgumentException
                ? exception.getMessage()
                : "ThreadCity could not open that incident bundle. Its content was not retained.");
    }

    private void analyzeBuiltInIncident() {
        cancelActiveWork();
        setActionsEnabled(true);
        replayTimeline.setVisible(false);
        render(analysisService.analyzeSample("Checkout outage · 02:14 UTC", "deadlock.txt"));
        revealAnalysis();
        selectWorkbenchPage(overviewTab);
    }

    private void replayCorrectedIncident() {
        cancelActiveWork();
        long generation = replayGeneration.incrementAndGet();
        render(analysisService.analyzeSample("Checkout outage · before the fix", "deadlock.txt"));
        prepareReplayTimeline();
        replayTimeline.setVisible(true);
        setReplayStatus("FIX REPLAY RUNNING", "Both operations now acquire PaymentLock before InventoryLock.");
        setActionsEnabled(false);
        revealAnalysis();
        selectWorkbenchPage(timelineTab);
        timeMachine.selectStage(2);

        UI ui = UI.getCurrent();
        Thread.ofVirtual().name("threadcity-corrected-replay").start(() -> {
            for (int index = 0; index < FIX_STEPS.size(); index++) {
                try {
                    Thread.sleep(java.time.Duration.ofMillis(650));
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (generation != replayGeneration.get() || !ui.isAttached()) {
                    return;
                }
                int activeStep = index;
                ui.access(() -> advanceCorrectedReplay(generation, activeStep));
            }
        });
    }

    private void prepareReplayTimeline() {
        replayTimeline.removeAll();
        replaySteps.clear();
        Div steps = new Div();
        steps.addClassName("timeline-steps");
        for (int index = 0; index < FIX_STEPS.size(); index++) {
            Span number = new Span(Integer.toString(index + 1));
            number.addClassName("timeline-number");
            Span text = new Span(FIX_STEPS.get(index));
            Div step = new Div(number, text);
            step.addClassName("timeline-step");
            replaySteps.add(step);
            steps.add(step);
        }
        replayTimeline.add(new H2("Corrected lock-order replay"), steps);
    }

    private void advanceCorrectedReplay(long generation, int activeStep) {
        if (generation != replayGeneration.get()) {
            return;
        }
        for (int index = 0; index < replaySteps.size(); index++) {
            replaySteps.get(index).removeClassNames("step-active", "step-complete");
            if (index < activeStep) {
                replaySteps.get(index).addClassName("step-complete");
            } else if (index == activeStep) {
                replaySteps.get(index).addClassName("step-active");
            }
        }
        setReplayStatus("FIX REPLAY · " + (activeStep + 1) + "/" + FIX_STEPS.size(), FIX_STEPS.get(activeStep));

        if (activeStep == FIX_STEPS.size() - 1) {
            replaySteps.forEach(step -> {
                step.removeClassName("step-active");
                step.addClassName("step-complete");
            });
            render(analysisService.analyzeSample("Checkout workload · corrected lock order", "corrected.txt"));
            setReplayStatus("TRAFFIC FLOWING NORMALLY", "Both operations completed with one consistent lock order.");
            setActionsEnabled(true);
            selectWorkbenchPage(timelineTab);
            timeMachine.selectStage(3);
        }
    }

    private void setReplayStatus(String label, String detail) {
        incidentStatus.removeAll();
        incidentStatus.removeClassNames("status-critical", "status-clear");
        Span labelText = new Span(label);
        labelText.addClassName("status-label");
        Span detailText = new Span(detail);
        detailText.addClassName("status-detail");
        incidentStatus.add(labelText, detailText);
        incidentStatus.addClassName(label.startsWith("TRAFFIC") ? "status-clear" : "status-critical");
    }

    private void cancelActiveReplays() {
        replayGeneration.incrementAndGet();
        timeMachine.cancelReplay();
    }

    private void cancelActiveWork() {
        cancelActiveReplays();
        analysisGeneration.incrementAndGet();
        if (activeAnalysis != null) {
            activeAnalysis.cancel(true);
            activeAnalysis = null;
        }
    }

    private <T> void runEvidenceTask(
            Supplier<T> work,
            Consumer<T> success,
            Function<RuntimeException, String> errorMessage) {
        UI ui = UI.getCurrent();
        long generation = analysisGeneration.incrementAndGet();
        setActionsEnabled(false);
        Optional<Future<?>> submitted = taskExecutor.trySubmit(() -> {
            try {
                T result = work.get();
                access(ui, () -> {
                    if (generation != analysisGeneration.get()) {
                        return;
                    }
                    activeAnalysis = null;
                    setActionsEnabled(true);
                    success.accept(result);
                });
            } catch (RuntimeException exception) {
                LOGGER.warn("Evidence analysis failed ({})", exception.getClass().getSimpleName(), exception);
                access(ui, () -> {
                    if (generation != analysisGeneration.get()) {
                        return;
                    }
                    activeAnalysis = null;
                    setActionsEnabled(true);
                    showError(errorMessage.apply(exception));
                });
            }
        });
        if (submitted.isEmpty()) {
            setActionsEnabled(true);
            showError("Two evidence analyses are already running. Try again in a moment.");
            return;
        }
        activeAnalysis = submitted.get();
    }

    private static void access(UI ui, Runnable action) {
        if (ui != null && ui.isAttached()) {
            ui.access(action::run);
        }
    }

    private void setActionsEnabled(boolean enabled) {
        replayButton.setEnabled(enabled);
        fixButton.setEnabled(enabled);
        uploadControls.setEnabled(enabled);
        jfrTimelinePanel.setControlsEnabled(enabled);
        copilotPanel.setControlsEnabled(enabled);
        timeMachine.setControlsEnabled(enabled);
        judgeModeButton.setEnabled(enabled);
    }

    private void navigateJudgeMode(int step) {
        if (requiresJudgeIncident(step, currentResult)) {
            cancelActiveWork();
            render(analysisService.analyzeSample("Judge tour · checkout deadlock", "deadlock.txt"));
            replayTimeline.setVisible(false);
            revealAnalysis();
        }
        switch (step) {
            case 0 -> {
                selectWorkbenchPage(overviewTab);
                trafficMap.getElement().callJsFunction("scrollIntoView", true);
            }
            case 1 -> {
                selectWorkbenchPage(overviewTab);
                blockerLeaderboard.getElement().callJsFunction("scrollIntoView", true);
            }
            case 2 -> {
                selectWorkbenchPage(synchronizersTab);
                synchronizerObservatory.getElement().callJsFunction("scrollIntoView", true);
            }
            case 3 -> {
                selectWorkbenchPage(cohortsTab);
                stackCohortExplorer.getElement().callJsFunction("scrollIntoView", true);
            }
            case 4 -> {
                multiDumpComparisonPanel.loadDemo();
                selectWorkbenchPage(compareTab);
                multiDumpComparisonPanel.getElement().callJsFunction("scrollIntoView", true);
            }
            case 5 -> {
                jfrTimelinePanel.loadDemo();
                selectWorkbenchPage(jfrTab);
                jfrTimelinePanel.getElement().callJsFunction("scrollIntoView", true);
            }
            case 6 -> {
                selectWorkbenchPage(overviewTab);
                reportDownload.getElement().callJsFunction("scrollIntoView", true);
            }
            default -> throw new IllegalArgumentException("Unknown judge-mode step: " + step);
        }
    }

    static boolean requiresJudgeIncident(int step, AnalysisResult currentResult) {
        return step == 0 || currentResult == null;
    }

    private void revealAnalysis() {
        analysisSection.setVisible(true);
        analysisSection.getElement().callJsFunction("scrollIntoView", true);
    }

    private void render(AnalysisResult result) {
        currentResult = result;
        copilotPanel.showResult(result);
        evidencePanel.showResult(result);
        synchronizerObservatory.render(result);
        stackCohortExplorer.render(result);
        parserConfidencePanel.render(result);
        incidentPatternPanel.render(result);
        jfrTimelinePanel.showResult(result);
        renderStatus(result);
        renderMetrics(result);
        trafficMap.render(result);
        blockerLeaderboard.render(result);
        renderFindings(result);
    }

    private void renderStatus(AnalysisResult result) {
        incidentStatus.removeAll();
        incidentStatus.removeClassNames("status-critical", "status-clear");

        Div copy = new Div();
        Span label = new Span(result.hasDeadlock()
                ? "PRIMARY SNAPSHOT · GRIDLOCK DETECTED"
                : "PRIMARY SNAPSHOT · NO DEADLOCK DETECTED");
        label.addClassName("status-label");
        Span detail = new Span(
                result.snapshot().sourceName() + " · " + result.snapshot().threads().size() + " threads analyzed");
        detail.addClassName("status-detail");
        Paragraph diagnosis = new Paragraph(IncidentNarrative.diagnosis(result));
        diagnosis.addClassName("status-diagnosis");
        copy.add(label, detail, diagnosis);

        Button copyButton = new Button("Copy incident summary", event -> copyIncidentSummary(result));
        copyButton.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        reportDownload.setEnabled(true);
        HorizontalLayout actions = new HorizontalLayout(copyButton, reportDownload, copilotPanel.actionButton());
        actions.addClassName("status-actions");
        actions.setPadding(false);
        actions.setSpacing(false);
        incidentStatus.add(copy, actions);
        incidentStatus.addClassName(result.hasDeadlock() ? "status-critical" : "status-clear");
    }

    private void copyIncidentSummary(AnalysisResult result) {
        String summary = "ThreadCity — " + result.snapshot().sourceName() + System.lineSeparator()
                + IncidentNarrative.diagnosis(result) + System.lineSeparator()
                + "Threads: " + result.snapshot().threads().size()
                + ", confirmed deadlocks: " + result.deadlocks().size();
        getElement().executeJs("navigator.clipboard.writeText($0)", summary);
        showSuccess("Incident summary copied");
    }

    private void renderMetrics(AnalysisResult result) {
        metrics.removeAll();
        Map<ThreadState, Long> stateCounts = result.stateCounts();
        metrics.add(
                metric("Threads", result.snapshot().threads().size(), "neutral"),
                metric("Runnable", stateCounts.get(ThreadState.RUNNABLE), "good"),
                metric("Waiting", stateCounts.get(ThreadState.WAITING)
                        + stateCounts.get(ThreadState.TIMED_WAITING), "waiting"),
                metric("Blocked", stateCounts.get(ThreadState.BLOCKED), "critical"),
                metric("Deadlocks", result.deadlocks().size(), result.hasDeadlock() ? "critical" : "good"));
    }

    private Component metric(String label, long value, String tone) {
        Span valueText = new Span(Long.toString(value));
        valueText.addClassName("metric-value");
        Span labelText = new Span(label);
        labelText.addClassName("metric-label");
        Div card = new Div(valueText, labelText);
        card.addClassNames("metric-card", "metric-" + tone);
        return card;
    }

    private void renderFindings(AnalysisResult result) {
        findings.removeAll();
        findings.add(new H2("Incident findings"));
        for (Finding finding : result.findings()) {
            Span severity = new Span(finding.severity().name());
            severity.addClassNames(
                    "finding-severity",
                    "severity-" + finding.severity().name().toLowerCase(Locale.ROOT));
            Div card = new Div(severity, new H2(finding.title()), new Paragraph(finding.explanation()));
            card.addClassName("finding-card");
            HorizontalLayout actions = new HorizontalLayout();
            actions.addClassName("finding-actions");
            if (!finding.threadNames().isEmpty()) {
                Button inspect = new Button("Inspect affected threads", event -> focusFinding(finding));
                inspect.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE);
                inspect.addClassName("finding-action");
                actions.add(inspect);
            }
            Button askAi = new Button("✦ Ask AI about finding", event -> askAiAboutFinding(finding));
            askAi.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE);
            askAi.addClassName("finding-action");
            askAi.setEnabled(aiAvailable);
            askAi.getElement().setAttribute(
                    "title",
                    aiAvailable ? "Ask about this finding" : "Set OPENAI_API_KEY to enable the AI copilot");
            actions.add(askAi);
            card.add(actions);
            findings.add(card);
        }
    }

    private void inspectThread(String threadName) {
        if (currentResult == null) {
            return;
        }
        selectWorkbenchPage(threadsTab);
        evidencePanel.inspectThread(threadName);
    }

    private void inspectThread(JavaThread thread) {
        if (currentResult == null) {
            return;
        }
        selectWorkbenchPage(threadsTab);
        evidencePanel.inspectThread(thread);
    }

    private void inspectLock(String lockId) {
        selectWorkbenchPage(overviewTab);
        trafficMap.highlightLock(lockId);
    }

    private void focusFinding(Finding finding) {
        selectWorkbenchPage(threadsTab);
        evidencePanel.focusThreads(finding.threadNames());
    }

    private void askAiAboutThread(JavaThread thread) {
        copilotPanel.ask("Explain why thread \"" + thread.name() + "\" is in " + thread.state()
                + ", cite the exact lock and owner evidence, and recommend the safest next diagnostic step.");
    }

    private void askAiAboutWait(WaitEdge edge) {
        copilotPanel.ask("Explain the wait where thread \"" + edge.waiter().name() + "\" waits for lock "
                + edge.lock().shortId() + " owned by \"" + edge.owner().name()
                + "\". Is it part of a confirmed deadlock, and how should I verify the fix?");
    }

    private void askAiAboutFinding(Finding finding) {
        copilotPanel.ask("Explain the deterministic finding \"" + finding.title()
                + "\", cite the affected threads and locks, and turn it into an actionable remediation checklist.");
    }

    private void navigateAiEvidence(AiEvidenceReference reference) {
        if (currentResult == null) {
            return;
        }
        switch (reference.kind()) {
            case THREAD -> inspectThread(reference.key());
            case LOCK -> {
                selectWorkbenchPage(overviewTab);
                trafficMap.highlightLock(reference.key());
            }
            case FINDING -> currentResult.findings().stream()
                    .filter(finding -> finding.title().equals(reference.key()))
                    .findFirst()
                    .ifPresent(this::focusFinding);
        }
    }

    private void clearAnalysis() {
        cancelActiveWork();
        judgeModeCoach.close();
        currentResult = null;
        reportDownload.setEnabled(false);
        copilotPanel.clear();
        evidencePanel.clear();
        synchronizerObservatory.clear();
        stackCohortExplorer.clear();
        parserConfidencePanel.clear();
        incidentPatternPanel.clear();
        jfrTimelinePanel.clear();
        multiDumpComparisonPanel.clear();
        replayTimeline.setVisible(false);
        uploadControls.clear();
        setActionsEnabled(true);
        timeMachine.reset();
        selectWorkbenchPage(overviewTab);
        analysisSection.setVisible(false);
        hero.getElement().callJsFunction("scrollIntoView", true);
    }

    private void showError(String message) {
        Notification notification = Notification.show(message, 5000, Notification.Position.TOP_CENTER);
        notification.addThemeVariants(NotificationVariant.LUMO_ERROR);
    }

    private void showSuccess(String message) {
        Notification notification = Notification.show(message, 3500, Notification.Position.TOP_CENTER);
        notification.addThemeVariants(NotificationVariant.LUMO_SUCCESS);
    }

    private static Tab tab(VaadinIcon icon, String label) {
        var graphic = icon.create();
        graphic.setSize("1rem");
        return new Tab(graphic, new Span(label));
    }

    private record BundleAnalysis(
            List<AnalysisResult> snapshots,
            AnalysisResult primary,
            Optional<JfrAnalysis> jfr,
            boolean hasAllThreads) {
    }
}
