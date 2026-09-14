package ca.bazlur.threadcity.ui.component;

import ca.bazlur.threadcity.application.JfrAnalysisService;
import ca.bazlur.threadcity.domain.AnalysisResult;
import ca.bazlur.threadcity.domain.JfrAnalysis;
import ca.bazlur.threadcity.domain.JfrEventCategory;
import ca.bazlur.threadcity.domain.JfrEventSample;
import ca.bazlur.threadcity.domain.JfrEventSummary;
import ca.bazlur.threadcity.domain.JavaThread;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.masterdetaillayout.MasterDetailLayout;
import com.vaadin.flow.component.combobox.MultiSelectComboBox;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.progressbar.ProgressBar;
import com.vaadin.flow.component.slider.IntegerSlider;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.component.upload.Upload;
import com.vaadin.flow.data.renderer.ComponentRenderer;
import com.vaadin.flow.data.value.ValueChangeMode;
import com.vaadin.flow.server.streams.InMemoryUploadHandler;
import com.vaadin.flow.server.streams.UploadMetadata;

import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Correlates bounded JFR evidence with the currently analyzed thread dump.
 */
public final class JfrTimelinePanel extends Div {

    private final JfrAnalysisService analysisService;
    private final Consumer<String> inspectThread;
    private final Consumer<String> successNotifier;
    private final Consumer<String> errorNotifier;
    private final Upload upload;
    private final Div emptyState = new Div();
    private final Div analysisContent = new Div();
    private final Div metrics = new Div();
    private final Div eventMix = new Div();
    private final MultiSelectComboBox<JfrEventCategory> categories = new MultiSelectComboBox<>("Event categories");
    private final TextField search = new TextField("Search JFR evidence");
    private final IntegerSlider scrubber = new IntegerSlider();
    private final ProgressBar scrubProgress = new ProgressBar();
    private final Span filterSummary = new Span();
    private final Grid<JfrEventSample> grid = new Grid<>();
    private final MasterDetailLayout workbench = new MasterDetailLayout();
    private List<JfrEventSample> visibleEvents = List.of();
    private AnalysisResult dumpAnalysis;
    private JfrAnalysis jfrAnalysis;
    private boolean updatingScrubber;

    public JfrTimelinePanel(
            JfrAnalysisService analysisService,
            Consumer<String> inspectThread,
            Consumer<String> successNotifier,
            Consumer<String> errorNotifier) {
        this.analysisService = analysisService;
        this.inspectThread = inspectThread;
        this.successNotifier = successNotifier;
        this.errorNotifier = errorNotifier;
        upload = createUpload();
        configureFilters();
        configureGrid();
        configureScrubber();
        buildLayout();
        renderEmpty();
    }

    public void showResult(AnalysisResult result) {
        dumpAnalysis = result;
        if (jfrAnalysis != null) {
            renderAnalysis();
        }
    }

    public void analyze(String sourceName, byte[] bytes) {
        try {
            jfrAnalysis = analysisService.analyze(sourceName, bytes);
            categories.clear();
            search.clear();
            renderAnalysis();
            successNotifier.accept(jfrAnalysis.relevantEvents() + " relevant JFR events analyzed");
        } catch (IllegalArgumentException exception) {
            errorNotifier.accept(exception.getMessage());
        }
    }

    public Optional<JfrAnalysis> currentAnalysis() {
        return Optional.ofNullable(jfrAnalysis);
    }

    public void loadDemo() {
        try {
            jfrAnalysis = analysisService.analyzeDemo();
            categories.clear();
            search.clear();
            renderAnalysis();
            successNotifier.accept("Live demo JFR evidence generated in memory");
        } catch (RuntimeException exception) {
            errorNotifier.accept(exception.getMessage());
        }
    }

    public void clear() {
        dumpAnalysis = null;
        jfrAnalysis = null;
        upload.clearFileList();
        categories.clear();
        search.clear();
        renderEmpty();
    }

    private Upload createUpload() {
        InMemoryUploadHandler handler = new InMemoryUploadHandler(this::handleUpload) {
            @Override
            public long getFileSizeMax() {
                return JfrAnalysisService.MAX_BYTES;
            }

            @Override
            public long getRequestSizeMax() {
                return JfrAnalysisService.MAX_BYTES + 64 * 1024L;
            }

            @Override
            public long getFileCountMax() {
                return 1;
            }
        };
        handler.whenComplete(success -> {
            if (!success) {
                errorNotifier.accept("The JFR upload could not be completed.");
            }
        });
        Upload component = new Upload(handler);
        component.setMaxFiles(1);
        component.setMaxFileSize(JfrAnalysisService.MAX_BYTES);
        component.setAcceptedFileTypes(".jfr", "application/octet-stream");
        component.setDropLabel(new Span("Drop a trusted .jfr recording"));
        Button button = new Button("Analyze JFR recording", VaadinIcon.UPLOAD.create());
        button.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        component.setUploadButton(button);
        component.addFileRejectedListener(event -> errorNotifier.accept(event.getErrorMessage()));
        component.addClassName("jfr-upload");
        return component;
    }

    private void handleUpload(UploadMetadata metadata, byte[] bytes) {
        analyze(metadata.fileName(), bytes);
    }

    private void configureFilters() {
        categories.setItems(JfrEventCategory.values());
        categories.setItemLabelGenerator(JfrEventCategory::label);
        categories.setClearButtonVisible(true);
        categories.setPlaceholder("All categories");
        categories.addValueChangeListener(event -> updateVisibleEvents());
        search.setPlaceholder("thread, frame, event, detail...");
        search.setClearButtonVisible(true);
        search.setValueChangeMode(ValueChangeMode.EAGER);
        search.addValueChangeListener(event -> updateVisibleEvents());
        search.setWidthFull();
    }

    private void configureScrubber() {
        scrubber.setMin(0);
        scrubber.setMax(0);
        scrubber.setStep(1);
        scrubber.setValue(0);
        scrubber.setValueAlwaysVisible(true);
        scrubber.setMinMaxVisible(true);
        scrubber.setWidthFull();
        scrubber.setAriaLabel("JFR event timeline position");
        scrubber.addValueChangeListener(event -> {
            if (!updatingScrubber && !visibleEvents.isEmpty()) {
                int index = Math.max(0, Math.min(event.getValue(), visibleEvents.size() - 1));
                JfrEventSample sample = visibleEvents.get(index);
                grid.select(sample);
                grid.scrollToItem(sample);
                scrubProgress.setValue(visibleEvents.size() == 1 ? 1.0 : (double) index / (visibleEvents.size() - 1));
            }
        });
        scrubProgress.setMin(0);
        scrubProgress.setMax(1);
        scrubProgress.setValue(0);
        scrubProgress.setWidthFull();
    }

    private void configureGrid() {
        grid.addClassName("jfr-event-grid");
        grid.addThemeVariants(GridVariant.LUMO_NO_BORDER, GridVariant.LUMO_ROW_STRIPES);
        grid.addColumn(this::offset).setHeader("Offset").setAutoWidth(true);
        grid.addColumn(new ComponentRenderer<>(sample -> categoryBadge(sample.category())))
                .setHeader("Category").setAutoWidth(true);
        grid.addColumn(JfrEventSample::eventLabel).setHeader("Event").setFlexGrow(2).setSortable(true);
        grid.addColumn(sample -> duration(sample.duration())).setHeader("Duration").setAutoWidth(true);
        grid.addColumn(sample -> sample.threadName() == null ? "—" : sample.threadName())
                .setHeader("Thread").setFlexGrow(2).setSortable(true);
        grid.addColumn(new ComponentRenderer<>(this::correlationBadge))
                .setHeader("Dump correlation").setAutoWidth(true);
        grid.addColumn(sample -> sample.topFrame() == null ? "—" : sample.topFrame())
                .setHeader("Top frame").setFlexGrow(3);
        grid.setHeight("430px");
        grid.asSingleSelect().addValueChangeListener(event -> {
            JfrEventSample sample = event.getValue();
            if (sample != null) {
                workbench.setDetail(detail(sample));
                int index = visibleEvents.indexOf(sample);
                if (index >= 0) {
                    updatingScrubber = true;
                    scrubber.setValue(index);
                    scrubProgress.setValue(visibleEvents.size() == 1 ? 1.0 : (double) index / (visibleEvents.size() - 1));
                    updatingScrubber = false;
                }
            }
        });
    }

    private void buildLayout() {
        Span eyebrow = new Span("JDK FLIGHT RECORDER");
        eyebrow.addClassName("eyebrow");
        Paragraph help = new Paragraph(
                "Correlate monitor, park, CPU, I/O, GC, and virtual-thread events with the current dump."
                        + " Recordings are bounded, processed once, and deleted from temporary storage.");
        help.addClassName("panel-help");
        Anchor exampleDownload = new Anchor(
                "/examples/threadcity-demo.jfr", "Download example threadcity-demo.jfr");
        exampleDownload.getElement().setAttribute("download", "threadcity-demo.jfr");
        exampleDownload.addClassName("example-download");
        Div uploadCard = new Div(upload, exampleDownload);
        uploadCard.addClassName("jfr-upload-card");

        emptyState.add(
                VaadinIcon.CLOCK.create(),
                new H2("Add time to the snapshot"),
                new Paragraph("Upload a trusted JFR recording to see what happened before and around the thread dump."));
        emptyState.addClassName("jfr-empty");

        HorizontalLayout filters = new HorizontalLayout(search, categories);
        filters.setAlignItems(HorizontalLayout.Alignment.END);
        filters.setWidthFull();
        filters.expand(search);
        filters.addClassName("jfr-filter-bar");
        filterSummary.addClassName("jfr-filter-summary");

        Div master = new Div(filters, filterSummary, scrubProgress, scrubber, grid);
        master.addClassName("jfr-master");
        Div placeholder = new Div(
                VaadinIcon.CURSOR_O.create(),
                new H2("Select an event"),
                new Paragraph("Timing, stack, detail, and thread-dump correlation will appear here."));
        placeholder.addClassName("inspector-placeholder");
        workbench.setMaster(master);
        workbench.setDetailPlaceholder(placeholder);
        workbench.setMasterSize("70%");
        workbench.setDetailSize("30%");
        workbench.setExpandMaster(true);
        workbench.setOverlaySize("min(100%, 620px)");
        workbench.setAnimationEnabled(true);
        workbench.addClassName("jfr-workbench");

        analysisContent.add(metrics, eventMix, workbench);
        analysisContent.addClassName("jfr-analysis-content");
        add(eyebrow, new H2("JFR Evidence Timeline"), help, uploadCard, emptyState, analysisContent);
        addClassNames("panel", "jfr-timeline-panel");
    }

    private void renderEmpty() {
        emptyState.setVisible(true);
        analysisContent.setVisible(false);
        grid.setItems(List.of());
        workbench.setDetail(null);
        filterSummary.setText("No recording loaded");
    }

    private void renderAnalysis() {
        emptyState.setVisible(false);
        analysisContent.setVisible(true);
        metrics.removeAll();
        Set<String> correlated = correlatedThreadNames();
        metrics.add(
                metric("Events read", jfrAnalysis.eventsRead()),
                metric("Relevant events", jfrAnalysis.relevantEvents()),
                metric("Recording span", duration(jfrAnalysis.recordingDuration())),
                metric("Dump matches", correlated.size()));
        renderEventMix();
        updateVisibleEvents();
    }

    private void renderEventMix() {
        eventMix.removeAll();
        long maximum = Math.max(1, jfrAnalysis.summaries().stream().mapToLong(JfrEventSummary::count).max().orElse(1));
        for (JfrEventSummary summary : jfrAnalysis.summaries()) {
            Span label = new Span(summary.eventLabel());
            Span count = new Span(Long.toString(summary.count()));
            ProgressBar bar = new ProgressBar(0, maximum, summary.count());
            Div row = new Div(label, count, bar);
            row.addClassName("jfr-mix-row");
            eventMix.add(row);
        }
    }

    private void updateVisibleEvents() {
        if (jfrAnalysis == null) {
            return;
        }
        Set<JfrEventCategory> selected = categories.getValue();
        String query = search.getValue() == null ? "" : search.getValue().strip().toLowerCase(Locale.ROOT);
        visibleEvents = jfrAnalysis.samples().stream()
                .filter(sample -> selected.isEmpty() || selected.contains(sample.category()))
                .filter(sample -> query.isEmpty()
                        || contains(sample.eventType(), query)
                        || contains(sample.eventLabel(), query)
                        || contains(sample.threadName(), query)
                        || contains(sample.topFrame(), query)
                        || contains(sample.detail(), query))
                .toList();
        grid.setItems(visibleEvents);
        workbench.setDetail(null);
        grid.deselectAll();
        updatingScrubber = true;
        scrubber.setMin(0);
        scrubber.setMax(Math.max(0, visibleEvents.size() - 1));
        scrubber.setValue(0);
        scrubber.setEnabled(!visibleEvents.isEmpty());
        scrubProgress.setValue(0);
        updatingScrubber = false;
        filterSummary.setText(visibleEvents.size() + " of " + jfrAnalysis.samples().size()
                + " retained events" + (jfrAnalysis.truncated() ? " · bounded result" : ""));
    }

    private Div detail(JfrEventSample sample) {
        Div panel = new Div();
        panel.addClassNames("panel", "jfr-event-detail");
        Span category = categoryBadge(sample.category());
        H2 title = new H2(sample.eventLabel());
        Paragraph type = new Paragraph(sample.eventType());
        type.addClassName("jfr-event-type");
        Div facts = new Div(
                fact("Started", DateTimeFormatter.ISO_INSTANT.format(sample.startTime())),
                fact("Offset", offset(sample)),
                fact("Duration", duration(sample.duration())),
                fact("Java thread ID", sample.javaThreadId() == null ? "—" : sample.javaThreadId().toString()));
        facts.addClassName("jfr-detail-facts");
        Paragraph thread = new Paragraph("Thread: " + (sample.threadName() == null ? "Not recorded" : sample.threadName()));
        Paragraph frame = new Paragraph("Top frame: " + (sample.topFrame() == null ? "Not recorded" : sample.topFrame()));
        Paragraph evidence = new Paragraph("Event detail: " + (sample.detail() == null ? "No additional field" : sample.detail()));
        Button inspect = new Button("Inspect matching dump thread", event -> inspectThread.accept(sample.threadName()));
        inspect.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        inspect.setEnabled(isCorrelated(sample));
        Button close = new Button("Close", event -> {
            workbench.setDetail(null);
            grid.deselectAll();
        });
        close.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        panel.add(category, title, type, facts, thread, frame, evidence, new HorizontalLayout(inspect, close));
        return panel;
    }

    private Component metric(String label, Object value) {
        Div metric = new Div(new Span(value.toString()), new Span(label));
        metric.addClassName("jfr-metric");
        return metric;
    }

    private Div fact(String label, String value) {
        Div fact = new Div(new Span(value), new Span(label));
        fact.addClassName("jfr-detail-fact");
        return fact;
    }

    private Span categoryBadge(JfrEventCategory category) {
        Span badge = new Span(category.label());
        badge.addClassNames("jfr-category", "jfr-category-" + category.name().toLowerCase(Locale.ROOT));
        return badge;
    }

    private Span correlationBadge(JfrEventSample sample) {
        boolean correlated = isCorrelated(sample);
        Span badge = new Span(correlated ? "Matched" : "Unmatched");
        badge.addClassNames("jfr-correlation", correlated ? "jfr-correlated" : "jfr-unmatched");
        return badge;
    }

    private boolean isCorrelated(JfrEventSample sample) {
        if (dumpAnalysis == null || sample.threadName() == null) {
            return false;
        }
        return dumpAnalysis.snapshot().threads().stream()
                .map(JavaThread::name)
                .anyMatch(sample.threadName()::equals);
    }

    private Set<String> correlatedThreadNames() {
        Set<String> names = new LinkedHashSet<>();
        if (jfrAnalysis != null) {
            jfrAnalysis.samples().stream().filter(this::isCorrelated).map(JfrEventSample::threadName).forEach(names::add);
        }
        return names;
    }

    private String offset(JfrEventSample sample) {
        if (jfrAnalysis == null || jfrAnalysis.startTime() == null) {
            return "—";
        }
        return "+" + duration(Duration.between(jfrAnalysis.startTime(), sample.startTime()));
    }

    private String duration(Duration duration) {
        if (duration == null) {
            return "0 ms";
        }
        long millis = duration.toMillis();
        if (millis < 1) {
            return duration.isZero() ? "0 ms" : "<1 ms";
        }
        if (millis < 1_000) {
            return millis + " ms";
        }
        return String.format(Locale.ROOT, "%.2f s", millis / 1_000.0);
    }

    private boolean contains(String value, String query) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(query);
    }
}
