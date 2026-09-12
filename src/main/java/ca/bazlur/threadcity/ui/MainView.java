package ca.bazlur.threadcity.ui;

import ca.bazlur.threadcity.ai.IncidentExplanationService;
import ca.bazlur.threadcity.application.ThreadDumpAnalysisService;
import ca.bazlur.threadcity.application.JfrAnalysisService;
import ca.bazlur.threadcity.application.IncidentBundleService;
import ca.bazlur.threadcity.domain.AnalysisResult;
import ca.bazlur.threadcity.domain.Finding;
import ca.bazlur.threadcity.domain.JavaThread;
import ca.bazlur.threadcity.domain.IncidentBundle;
import ca.bazlur.threadcity.domain.ThreadState;
import ca.bazlur.threadcity.domain.WaitEdge;
import ca.bazlur.threadcity.parser.ThreadDumpUploadValidator;
import ca.bazlur.threadcity.ui.component.AiCopilotPanel;
import ca.bazlur.threadcity.ui.component.BlockerLeaderboard;
import ca.bazlur.threadcity.ui.component.IncidentComparisonPanel;
import ca.bazlur.threadcity.ui.component.IncidentTimeMachine;
import ca.bazlur.threadcity.ui.component.IncidentPatternPanel;
import ca.bazlur.threadcity.ui.component.LockTrafficMap;
import ca.bazlur.threadcity.ui.component.MultiDumpComparisonPanel;
import ca.bazlur.threadcity.ui.component.ParserConfidencePanel;
import ca.bazlur.threadcity.ui.component.JfrTimelinePanel;
import ca.bazlur.threadcity.ui.component.SynchronizerObservatory;
import ca.bazlur.threadcity.ui.component.StackCohortExplorer;
import ca.bazlur.threadcity.ui.component.ThreadEvidencePanel;
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
import com.vaadin.flow.component.tabs.Tabs;
import com.vaadin.flow.component.tabs.TabsVariant;
import com.vaadin.flow.component.upload.Upload;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.streams.InMemoryUploadHandler;
import com.vaadin.flow.server.streams.UploadMetadata;
import com.vaadin.flow.shared.Registration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@Route(value = "", layout = MainLayout.class)
@PageTitle("ThreadCity — JVM traffic control")
public class MainView extends Div {

    private static final List<String> FIX_STEPS = List.of(
            "Payment lock acquired",
            "Inventory lock requested in the same order",
            "Second operation queues behind that ordering",
            "First operation completes and releases both locks",
            "Second operation completes",
            "Traffic flowing normally");

    private final ThreadDumpAnalysisService analysisService;
    private final IncidentBundleService bundleService;
    private final boolean aiAvailable;
    private final ThreadDumpUploadValidator uploadValidator = new ThreadDumpUploadValidator();
    private final AtomicLong replayGeneration = new AtomicLong();

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
    private final Tabs workbenchTabs = new Tabs();
    private final Tab overviewTab = tab(VaadinIcon.MAP_MARKER, "Incident map");
    private final Tab timelineTab = tab(VaadinIcon.TIME_BACKWARD, "Time machine");
    private final Tab threadsTab = tab(VaadinIcon.TABLE, "Threads & evidence");
    private final Tab synchronizersTab = tab(VaadinIcon.LOCK, "Synchronizers");
    private final Tab cohortsTab = tab(VaadinIcon.CLUSTER, "Stack cohorts");
    private final Tab jfrTab = tab(VaadinIcon.CLOCK, "JFR timeline");
    private final Tab compareTab = tab(VaadinIcon.SPLIT, "Compare fix");
    private final Tab copilotTab = tab(VaadinIcon.CHAT, "AI copilot");
    private final Map<Tab, Component> workbenchPages = new LinkedHashMap<>();

    private final Button replayButton = new Button("Replay a production deadlock");
    private final Button fixButton = new Button("Replay with the fix");
    private final Button clearButton = new Button("Clear analysis");
    private final Upload upload;
    private final Upload bundleUpload;
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

    private AnalysisResult currentResult;
    private Registration resizeRegistration;

    public MainView(
            IncidentExplanationService incidentExplanationService,
            ThreadDumpAnalysisService analysisService,
            JfrAnalysisService jfrAnalysisService,
            IncidentBundleService bundleService) {
        this.analysisService = analysisService;
        this.bundleService = bundleService;
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
                jfrAnalysisService, this::inspectThread, this::showSuccess, this::showError);
        timeMachine = new IncidentTimeMachine(analysisService, this::inspectThread);
        comparisonPanel = new IncidentComparisonPanel(analysisService);
        multiDumpComparisonPanel = new MultiDumpComparisonPanel(analysisService, this::showSuccess, this::showError);
        copilotPanel = new AiCopilotPanel(
                incidentExplanationService,
                () -> selectWorkbenchPage(copilotTab),
                this::showError,
                this::navigateAiEvidence);
        upload = createUpload();
        bundleUpload = createBundleUpload();

        configureActions();
        addClassName("app-shell");
        add(buildHero(), buildAnalysisSection());
        registerLifecycleListeners();
    }

    private void registerLifecycleListeners() {
        addAttachListener(event -> {
            UI ui = event.getUI();
            ui.getPage().retrieveExtendedClientDetails(
                    details -> evidencePanel.updateResponsiveMode(details.getWindowInnerWidth()));
            resizeRegistration = ui.getPage().addBrowserWindowResizeListener(
                    resize -> evidencePanel.updateResponsiveMode(resize.getWidth()));
        });
        addDetachListener(event -> {
            replayGeneration.incrementAndGet();
            timeMachine.cancelReplay();
            if (resizeRegistration != null) {
                resizeRegistration.remove();
                resizeRegistration = null;
            }
        });
    }

    private Upload createUpload() {
        InMemoryUploadHandler handler = new InMemoryUploadHandler(this::handleUpload) {
            @Override
            public long getFileSizeMax() {
                return ThreadDumpUploadValidator.MAX_BYTES;
            }

            @Override
            public long getRequestSizeMax() {
                return ThreadDumpUploadValidator.MAX_BYTES + 64 * 1024L;
            }

            @Override
            public long getFileCountMax() {
                return 1;
            }
        };
        handler.whenComplete(success -> {
            if (!success) {
                showError("Upload failed. The file was not retained.");
            }
        });

        Upload component = new Upload(handler);
        component.setMaxFiles(1);
        component.setMaxFileSize(ThreadDumpUploadValidator.MAX_BYTES);
        component.setAcceptedFileTypes(".txt", ".log", "text/plain");
        component.setDropLabel(new Span("Drop a jstack .txt or .log here"));
        Button chooseFile = new Button("Analyze your thread dump");
        chooseFile.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        component.setUploadButton(chooseFile);
        component.addClassName("dump-upload");
        component.addFileRejectedListener(event -> showError(normalizeUploadError(event.getErrorMessage())));
        return component;
    }

    private Upload createBundleUpload() {
        InMemoryUploadHandler handler = new InMemoryUploadHandler(this::handleBundleUpload) {
            @Override
            public long getFileSizeMax() {
                return IncidentBundleService.MAX_BUNDLE_BYTES;
            }

            @Override
            public long getRequestSizeMax() {
                return IncidentBundleService.MAX_BUNDLE_BYTES + 64 * 1024L;
            }

            @Override
            public long getFileCountMax() {
                return 1;
            }
        };
        handler.whenComplete(success -> {
            if (!success) {
                showError("Incident bundle upload failed. The file was not retained.");
            }
        });
        Upload component = new Upload(handler);
        component.setMaxFiles(1);
        component.setMaxFileSize(IncidentBundleService.MAX_BUNDLE_BYTES);
        component.setAcceptedFileTypes(".threadcity", "application/zip", "application/octet-stream");
        component.setDropLabel(new Span("Drop a collector .threadcity bundle"));
        Button chooseFile = new Button("Open complete incident bundle");
        chooseFile.addThemeVariants(ButtonVariant.LUMO_CONTRAST);
        component.setUploadButton(chooseFile);
        component.addClassName("bundle-upload");
        component.addFileRejectedListener(event -> showError("Choose one .threadcity bundle up to 48 MiB"));
        return component;
    }

    private void configureActions() {
        replayButton.addClickListener(event -> analyzeBuiltInIncident());
        replayButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY, ButtonVariant.LUMO_LARGE);
        replayButton.addClassName("hero-action");

        fixButton.addClickListener(event -> replayCorrectedIncident());
        fixButton.addThemeVariants(ButtonVariant.LUMO_CONTRAST, ButtonVariant.LUMO_LARGE);

        clearButton.addClickListener(event -> clearAnalysis());
        clearButton.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
    }

    private Component buildHero() {
        Span eyebrow = new Span("JVM TRAFFIC CONTROL");
        eyebrow.addClassName("eyebrow");
        H1 title = new H1("See exactly who is blocking whom.");
        Paragraph description = new Paragraph(
                "Turn an unreadable Java thread dump into an interactive map of lock ownership, circular waits, and repeated work.");
        description.addClassName("hero-copy");

        HorizontalLayout actions = new HorizontalLayout(replayButton, fixButton);
        actions.addClassName("hero-actions");
        actions.setPadding(false);
        Span hint = new Span("No sign-in · Nothing persisted · Deterministic Java core · AI is opt-in");
        hint.addClassName("hero-hint");

        Div uploadCard = new Div(upload);
        uploadCard.addClassName("upload-card");
        Span uploadPrivacy = new Span("UTF-8 text only · 5 MiB maximum · Released when you clear the analysis");
        uploadPrivacy.addClassName("upload-privacy");
        Anchor exampleDownload = new Anchor("/examples/jstack.txt", "Download example jstack.txt");
        exampleDownload.getElement().setAttribute("download", "jstack.txt");
        exampleDownload.addClassName("example-download");
        Div uploadFooter = new Div(uploadPrivacy, exampleDownload);
        uploadFooter.addClassName("upload-footer");
        uploadCard.add(uploadFooter);

        Span bundleLabel = new Span("FULL INCIDENT WINDOW");
        bundleLabel.addClassName("bundle-upload-label");
        Div bundleCard = new Div(
                bundleLabel,
                new Paragraph("Open 2–5 chronological dumps plus JFR in one step."),
                bundleUpload);
        bundleCard.addClassNames("upload-card", "bundle-upload-card");

        VerticalLayout content = new VerticalLayout(
                eyebrow, title, description, actions, hint, uploadCard, bundleCard);
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
        configureWorkbenchTabs();

        HorizontalLayout footerActions = new HorizontalLayout(clearButton);
        footerActions.addClassName("analysis-actions");
        Div pages = new Div(
                overviewPage,
                timelinePage,
                threadsPage,
                synchronizersPage,
                cohortsPage,
                jfrPage,
                comparePage,
                copilotPage);
        pages.addClassName("workbench-pages");
        analysisSection.add(incidentStatus, workbenchTabs, pages, footerActions);
        return analysisSection;
    }

    private void configureWorkbenchTabs() {
        workbenchTabs.add(
                overviewTab, timelineTab, threadsTab, synchronizersTab, cohortsTab, jfrTab, compareTab, copilotTab);
        workbenchTabs.addThemeVariants(TabsVariant.LUMO_EQUAL_WIDTH_TABS);
        workbenchTabs.addClassName("workbench-tabs");
        workbenchTabs.setWidthFull();

        workbenchPages.put(overviewTab, overviewPage);
        workbenchPages.put(timelineTab, timelinePage);
        workbenchPages.put(threadsTab, threadsPage);
        workbenchPages.put(synchronizersTab, synchronizersPage);
        workbenchPages.put(cohortsTab, cohortsPage);
        workbenchPages.put(jfrTab, jfrPage);
        workbenchPages.put(compareTab, comparePage);
        workbenchPages.put(copilotTab, copilotPage);
        workbenchPages.values().forEach(page -> {
            page.addClassName("workbench-page");
            page.setVisible(page == overviewPage);
        });
        workbenchTabs.addSelectedChangeListener(event -> showWorkbenchPage(event.getSelectedTab()));
    }

    private void showWorkbenchPage(Tab selected) {
        workbenchPages.forEach((tab, page) -> page.setVisible(tab == selected));
    }

    private void selectWorkbenchPage(Tab tab) {
        workbenchTabs.setSelectedTab(tab);
        showWorkbenchPage(tab);
    }

    private void handleUpload(UploadMetadata metadata, byte[] bytes) {
        cancelActiveReplays();
        try {
            ThreadDumpUploadValidator.ValidatedUpload validated = uploadValidator.validate(
                    metadata.fileName(), metadata.contentType(), bytes);
            AnalysisResult result = analysisService.analyze(validated.sourceName(), validated.content());
            replayTimeline.setVisible(false);
            render(result);
            revealAnalysis();
            selectWorkbenchPage(overviewTab);
            upload.clearFileList();
            showSuccess(result.snapshot().threads().size() + " threads analyzed. The original upload was not retained.");
        } catch (IllegalArgumentException exception) {
            upload.clearFileList();
            showError(exception.getMessage());
        } catch (RuntimeException exception) {
            upload.clearFileList();
            showError("ThreadCity could not analyze that file. Its content was not retained.");
        }
    }

    private void handleBundleUpload(UploadMetadata metadata, byte[] bytes) {
        cancelActiveReplays();
        try {
            IncidentBundle bundle = bundleService.read(metadata.fileName(), bytes);
            List<AnalysisResult> snapshots = bundle.threadDumps().stream()
                    .map(dump -> analysisService.analyze(dump.sourceName(), dump.content()))
                    .toList();
            AnalysisResult latest = snapshots.getLast();
            multiDumpComparisonPanel.setSnapshots(snapshots);
            render(latest);
            bundle.recording().ifPresent(recording -> jfrTimelinePanel.analyze("recording.jfr", recording));
            replayTimeline.setVisible(false);
            revealAnalysis();
            selectWorkbenchPage(overviewTab);
            bundleUpload.clearFileList();
            showSuccess("Incident window opened: " + snapshots.size()
                    + " dumps plus JFR. The original bundle was not retained.");
        } catch (IllegalArgumentException exception) {
            bundleUpload.clearFileList();
            showError(exception.getMessage());
        } catch (RuntimeException exception) {
            bundleUpload.clearFileList();
            showError("ThreadCity could not open that incident bundle. Its content was not retained.");
        }
    }

    private void analyzeBuiltInIncident() {
        cancelActiveReplays();
        setActionsEnabled(true);
        replayTimeline.setVisible(false);
        render(analysisService.analyzeSample("Checkout outage · 02:14 UTC", "deadlock.txt"));
        revealAnalysis();
        selectWorkbenchPage(overviewTab);
    }

    private void replayCorrectedIncident() {
        timeMachine.cancelReplay();
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
                    Thread.sleep(650);
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

    private void setActionsEnabled(boolean enabled) {
        replayButton.setEnabled(enabled);
        fixButton.setEnabled(enabled);
        upload.setEnabled(enabled);
        bundleUpload.setEnabled(enabled);
        copilotPanel.setControlsEnabled(enabled);
        timeMachine.setControlsEnabled(enabled);
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
        HorizontalLayout actions = new HorizontalLayout(copyButton, copilotPanel.actionButton());
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
        metrics.add(
                metric("Threads", result.snapshot().threads().size(), "neutral"),
                metric("Runnable", result.stateCounts().get(ThreadState.RUNNABLE), "good"),
                metric("Waiting", result.stateCounts().get(ThreadState.WAITING)
                        + result.stateCounts().get(ThreadState.TIMED_WAITING), "waiting"),
                metric("Blocked", result.stateCounts().get(ThreadState.BLOCKED), "critical"),
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
            if (aiAvailable) {
                Button askAi = new Button("✦ Ask AI about finding", event -> askAiAboutFinding(finding));
                askAi.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE);
                askAi.addClassName("finding-action");
                actions.add(askAi);
            }
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
        cancelActiveReplays();
        currentResult = null;
        copilotPanel.clear();
        evidencePanel.clear();
        synchronizerObservatory.clear();
        stackCohortExplorer.clear();
        parserConfidencePanel.clear();
        incidentPatternPanel.clear();
        jfrTimelinePanel.clear();
        multiDumpComparisonPanel.clear();
        replayTimeline.setVisible(false);
        upload.clearFileList();
        bundleUpload.clearFileList();
        setActionsEnabled(true);
        timeMachine.reset();
        selectWorkbenchPage(overviewTab);
        analysisSection.setVisible(false);
        hero.getElement().callJsFunction("scrollIntoView", true);
    }

    private String normalizeUploadError(String message) {
        if (message == null || message.isBlank()) {
            return "The file was rejected. Use a .txt or .log file up to 5 MiB.";
        }
        String normalized = message.toLowerCase(Locale.ROOT);
        if (normalized.contains("large") || normalized.contains("size")) {
            return "The uploaded file exceeds the 5 MiB limit";
        }
        if (normalized.contains("type") || normalized.contains("format")) {
            return "Choose a .txt or .log thread dump";
        }
        return "The file was rejected. Use a .txt or .log file up to 5 MiB.";
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
}
