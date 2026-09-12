package ca.bazlur.threadcity.ui.component;

import ca.bazlur.threadcity.analysis.SnapshotDiffAnalyzer;
import ca.bazlur.threadcity.application.ThreadDumpAnalysisService;
import ca.bazlur.threadcity.domain.AnalysisResult;
import ca.bazlur.threadcity.domain.SnapshotDiff;
import ca.bazlur.threadcity.domain.ThreadChange;
import ca.bazlur.threadcity.parser.ThreadDumpUploadValidator;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.splitlayout.SplitLayout;
import com.vaadin.flow.component.tabs.Tab;
import com.vaadin.flow.component.tabs.Tabs;
import com.vaadin.flow.component.upload.Upload;
import com.vaadin.flow.data.renderer.ComponentRenderer;
import com.vaadin.flow.server.streams.InMemoryUploadHandler;
import com.vaadin.flow.server.streams.UploadMetadata;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Builds a chronological case from real uploads and compares every adjacent pair.
 */
public final class MultiDumpComparisonPanel extends Div {

    private static final int MAX_SNAPSHOTS = 5;

    private final ThreadDumpAnalysisService analysisService;
    private final Consumer<String> successNotifier;
    private final Consumer<String> errorNotifier;
    private final ThreadDumpUploadValidator uploadValidator = new ThreadDumpUploadValidator();
    private final SnapshotDiffAnalyzer diffAnalyzer = new SnapshotDiffAnalyzer();
    private final List<AnalysisResult> snapshots = new ArrayList<>();
    private final Tabs timeline = new Tabs();
    private final Div emptyState = new Div();
    private final Div diffContent = new Div();
    private final Div metrics = new Div();
    private final Div beforeCard = new Div();
    private final Div afterCard = new Div();
    private final Grid<ThreadChange> changes = new Grid<>();
    private final Upload upload;
    private boolean renderingTabs;

    public MultiDumpComparisonPanel(
            ThreadDumpAnalysisService analysisService,
            Consumer<String> successNotifier,
            Consumer<String> errorNotifier) {
        this.analysisService = analysisService;
        this.successNotifier = successNotifier;
        this.errorNotifier = errorNotifier;
        upload = createUpload();
        configureTimeline();
        configureChangesGrid();
        buildLayout();
        renderTimeline();
    }

    public void clear() {
        snapshots.clear();
        upload.clearFileList();
        renderTimeline();
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
                return MAX_SNAPSHOTS;
            }
        };
        handler.whenComplete(success -> {
            if (!success) {
                errorNotifier.accept("One or more comparison dumps could not be uploaded.");
            }
        });
        Upload component = new Upload(handler);
        component.setMaxFiles(MAX_SNAPSHOTS);
        component.setMaxFileSize(ThreadDumpUploadValidator.MAX_BYTES);
        component.setAcceptedFileTypes(".txt", ".log", "text/plain");
        component.setDropLabel(new Span("Drop 2–5 chronological dumps"));
        Button uploadButton = new Button("Add thread dumps", VaadinIcon.UPLOAD.create());
        uploadButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        component.setUploadButton(uploadButton);
        component.addFileRejectedListener(event -> errorNotifier.accept(event.getErrorMessage()));
        component.addClassName("comparison-upload");
        return component;
    }

    private void handleUpload(UploadMetadata metadata, byte[] bytes) {
        if (snapshots.size() >= MAX_SNAPSHOTS) {
            errorNotifier.accept("A comparison case can contain at most five snapshots.");
            return;
        }
        try {
            ThreadDumpUploadValidator.ValidatedUpload validated = uploadValidator.validate(
                    metadata.fileName(), metadata.contentType(), bytes);
            snapshots.add(analysisService.analyze(validated.sourceName(), validated.content()));
            renderTimeline();
            successNotifier.accept(validated.sourceName() + " added as snapshot " + snapshots.size());
        } catch (IllegalArgumentException exception) {
            errorNotifier.accept(exception.getMessage());
        } catch (RuntimeException exception) {
            errorNotifier.accept("ThreadCity could not compare " + metadata.fileName() + ".");
        }
    }

    private void configureTimeline() {
        timeline.addClassName("comparison-timeline");
        timeline.setWidthFull();
        timeline.addSelectedChangeListener(event -> {
            if (!renderingTabs) {
                renderSelection(timeline.getSelectedIndex());
            }
        });
    }

    private void configureChangesGrid() {
        changes.addClassName("snapshot-changes-grid");
        changes.addThemeVariants(GridVariant.LUMO_NO_BORDER, GridVariant.LUMO_ROW_STRIPES);
        changes.addColumn(new ComponentRenderer<>(this::changeBadge)).setHeader("Change").setAutoWidth(true);
        changes.addColumn(ThreadChange::identity).setHeader("Thread").setFlexGrow(2).setSortable(true);
        changes.addColumn(ThreadChange::beforeState).setHeader("Before").setAutoWidth(true);
        changes.addColumn(ThreadChange::afterState).setHeader("After").setAutoWidth(true);
        changes.addColumn(change -> changedFrame(change) ? change.afterTopFrame() : "—")
                .setHeader("New top frame").setFlexGrow(3);
        changes.setHeight("340px");
    }

    private void buildLayout() {
        Span eyebrow = new Span("REAL SNAPSHOT SERIES");
        eyebrow.addClassName("eyebrow");
        Paragraph help = new Paragraph(
                "Upload dumps oldest first. ThreadCity aligns duplicate names safely and compares every adjacent pair.");
        help.addClassName("panel-help");
        Button demo = new Button("Load four-stage demo", VaadinIcon.PLAY.create(), event -> loadDemo());
        demo.addThemeVariants(ButtonVariant.LUMO_CONTRAST);
        Button clear = new Button("Clear case", VaadinIcon.TRASH.create(), event -> clear());
        clear.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        HorizontalLayout actions = new HorizontalLayout(demo, clear);
        actions.addClassName("comparison-case-actions");

        Div uploadZone = new Div(upload, actions);
        uploadZone.addClassName("comparison-upload-zone");

        emptyState.add(
                VaadinIcon.CLOCK.create(),
                new H2("Build an incident timeline"),
                new Paragraph("Add at least two real dumps, or load the four-stage demo to inspect change over time."));
        emptyState.addClassName("comparison-empty");

        SplitLayout split = new SplitLayout(beforeCard, afterCard);
        split.setSplitterPosition(50);
        split.setWidthFull();
        split.addClassName("snapshot-diff-split");
        diffContent.add(metrics, split, new H2("Thread-by-thread changes"), changes);
        diffContent.addClassName("snapshot-diff-content");

        add(eyebrow, new H2("Multi-dump case comparison"), help, uploadZone, timeline, emptyState, diffContent);
        addClassNames("panel", "multi-dump-comparison");
    }

    private void loadDemo() {
        snapshots.clear();
        snapshots.add(analysisService.analyzeSample("T−10s · healthy", "corrected.txt"));
        snapshots.add(analysisService.analyzeSample("T−2s · contention", "contention.txt"));
        snapshots.add(analysisService.analyzeSample("T0 · deadlock", "deadlock.txt"));
        snapshots.add(analysisService.analyzeSample("T+fix · recovered", "corrected.txt"));
        upload.clearFileList();
        renderTimeline();
        successNotifier.accept("Four forensic snapshots loaded");
    }

    private void renderTimeline() {
        renderingTabs = true;
        timeline.removeAll();
        for (int index = 0; index < snapshots.size(); index++) {
            AnalysisResult snapshot = snapshots.get(index);
            timeline.add(new Tab(new Span(Integer.toString(index + 1)), new Span(snapshot.snapshot().sourceName())));
        }
        timeline.setVisible(!snapshots.isEmpty());
        emptyState.setVisible(snapshots.size() < 2);
        diffContent.setVisible(snapshots.size() >= 2);
        if (!snapshots.isEmpty()) {
            timeline.setSelectedIndex(snapshots.size() - 1);
        }
        renderingTabs = false;
        if (snapshots.size() >= 2) {
            renderSelection(snapshots.size() - 1);
        }
    }

    private void renderSelection(int selectedIndex) {
        if (snapshots.size() < 2) {
            return;
        }
        int afterIndex = Math.max(1, Math.min(selectedIndex, snapshots.size() - 1));
        SnapshotDiff diff = diffAnalyzer.compare(snapshots.get(afterIndex - 1), snapshots.get(afterIndex));
        renderDiff(diff);
    }

    private void renderDiff(SnapshotDiff diff) {
        metrics.removeAll();
        metrics.add(
                metric("Changed threads", diff.changedThreadCount(), "neutral"),
                metric("New waits", diff.newWaitEdges(), diff.newWaitEdges() > 0 ? "critical" : "good"),
                metric("Resolved waits", diff.resolvedWaitEdges(), "good"),
                metric("Persistent waits", diff.persistentWaitEdges(), diff.persistentWaitEdges() > 0 ? "waiting" : "good"),
                metric("Deadlock delta", diff.newDeadlocks() - diff.resolvedDeadlocks(),
                        diff.newDeadlocks() > 0 ? "critical" : "good"));

        renderSnapshotCard(beforeCard, "BEFORE", diff.before());
        renderSnapshotCard(afterCard, "AFTER", diff.after());
        changes.setItems(diff.threadChanges().stream()
                .sorted(Comparator.comparing(ThreadChange::changed).reversed()
                        .thenComparing(ThreadChange::identity))
                .toList());
    }

    private void renderSnapshotCard(Div card, String label, AnalysisResult result) {
        card.removeAll();
        Span badge = new Span(label);
        badge.addClassName("snapshot-side-label");
        Span state = new Span(result.hasDeadlock() ? "GRIDLOCK" : result.waitEdges().isEmpty() ? "FLOWING" : "PRESSURE");
        state.addClassNames("snapshot-side-state", result.hasDeadlock() ? "snapshot-critical" : "snapshot-stable");
        card.add(
                badge,
                state,
                new H2(result.snapshot().sourceName()),
                new Paragraph(result.snapshot().threads().size() + " threads · "
                        + result.waitEdges().size() + " waits · " + result.deadlocks().size() + " deadlocks"));
        card.addClassName("snapshot-side");
    }

    private Component metric(String label, long value, String tone) {
        Div metric = new Div(new Span(Long.toString(value)), new Span(label));
        metric.addClassNames("snapshot-diff-metric", "snapshot-diff-" + tone);
        return metric;
    }

    private Span changeBadge(ThreadChange change) {
        String label = change.kinds().stream().map(ThreadChange.Kind::label).collect(Collectors.joining(" · "));
        Span badge = new Span(label);
        String tone = change.kinds().contains(ThreadChange.Kind.ADDED)
                || change.kinds().contains(ThreadChange.Kind.WAIT_CHANGED)
                ? "warning"
                : change.kinds().contains(ThreadChange.Kind.REMOVED) ? "good" : "neutral";
        badge.addClassNames("thread-change-badge", "thread-change-" + tone);
        return badge;
    }

    private boolean changedFrame(ThreadChange change) {
        return change.after() != null && change.kinds().contains(ThreadChange.Kind.STACK_CHANGED);
    }
}
