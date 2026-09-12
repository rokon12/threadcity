package ca.bazlur.threadcity.ui.component;

import ca.bazlur.threadcity.domain.AnalysisResult;
import ca.bazlur.threadcity.domain.ParserDiagnostics;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.details.Details;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.progressbar.ProgressBar;

import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Makes parser coverage and bounded unrecognized input evidence visible.
 */
public final class ParserConfidencePanel extends Div {

    private final Consumer<String> successNotifier;
    private final Span confidence = new Span();
    private final Span coverageLabel = new Span("No snapshot loaded");
    private final ProgressBar coverage = new ProgressBar(0, 1, 0);
    private final Div metrics = new Div();
    private final Grid<ParserDiagnostics.IgnoredLine> ignoredGrid = new Grid<>();
    private final Span ignoredNote = new Span();
    private final Details ignoredDetails;
    private ParserDiagnostics diagnostics = ParserDiagnostics.empty();

    public ParserConfidencePanel(Consumer<String> successNotifier) {
        this.successNotifier = successNotifier;
        configureGrid();
        ignoredDetails = buildIgnoredDetails();
        buildLayout();
    }

    public void render(AnalysisResult result) {
        diagnostics = result.snapshot().parserDiagnostics();
        confidence.setText(diagnostics.confidence().label());
        confidence.removeClassNames("parser-high", "parser-partial", "parser-low");
        confidence.addClassName("parser-" + diagnostics.confidence().name().toLowerCase());
        coverage.setValue(diagnostics.coverageRatio());
        coverageLabel.setText(diagnostics.coveragePercent() + "% of meaningful lines recognized");
        metrics.removeAll();
        metrics.add(
                metric("Meaningful lines", diagnostics.contentLines()),
                metric("Recognized", diagnostics.recognizedLines()),
                metric("Ignored", diagnostics.ignoredLines()),
                metric("Samples retained", diagnostics.ignoredLineSamples().size()));
        ignoredGrid.setItems(diagnostics.ignoredLineSamples());
        ignoredDetails.setSummaryText("Ignored input evidence · " + diagnostics.ignoredLines());
        ignoredDetails.setOpened(diagnostics.confidence() == ParserDiagnostics.Confidence.LOW);
        ignoredNote.setText(diagnostics.ignoredLines() == 0
                ? "Every meaningful line in this dump was recognized."
                : "Only bounded, sanitized samples are retained in the Vaadin session."
                        + (diagnostics.omittedIgnoredLines() == 0
                                ? ""
                                : " " + diagnostics.omittedIgnoredLines() + " additional lines were not sampled."));
    }

    public void clear() {
        diagnostics = ParserDiagnostics.empty();
        confidence.setText("");
        coverage.setValue(0);
        coverageLabel.setText("No snapshot loaded");
        metrics.removeAll();
        ignoredGrid.setItems(List.of());
        ignoredDetails.setSummaryText("Ignored input evidence · 0");
        ignoredDetails.setOpened(false);
        ignoredNote.setText("");
    }

    private void configureGrid() {
        ignoredGrid.addClassName("ignored-lines-grid");
        ignoredGrid.addThemeVariants(GridVariant.LUMO_NO_BORDER, GridVariant.LUMO_ROW_STRIPES);
        ignoredGrid.addColumn(ParserDiagnostics.IgnoredLine::occurrences)
                .setHeader("Count").setAutoWidth(true);
        ignoredGrid.addColumn(ParserDiagnostics.IgnoredLine::text)
                .setHeader("Sanitized line sample").setFlexGrow(4);
        ignoredGrid.setHeight("260px");
    }

    private Details buildIgnoredDetails() {
        Div content = new Div(ignoredNote, ignoredGrid);
        content.addClassName("ignored-lines-content");
        ignoredNote.addClassName("ignored-lines-note");
        Details details = new Details("Ignored input evidence · 0", content);
        details.addClassName("ignored-lines-details");
        return details;
    }

    private void buildLayout() {
        Span eyebrow = new Span("PARSER TRANSPARENCY");
        eyebrow.addClassName("eyebrow");
        Paragraph help = new Paragraph(
                "Coverage measures nonblank dump lines. Unknown input remains visible instead of being silently discarded.");
        help.addClassName("panel-help");
        confidence.addClassNames("parser-confidence", "parser-high");
        coverageLabel.addClassName("parser-coverage-label");
        coverage.setWidthFull();
        coverage.addClassName("parser-coverage");
        metrics.addClassName("parser-metrics");

        Button copy = new Button("Copy parser diagnostics", VaadinIcon.COPY.create(), event -> copyDiagnostics());
        copy.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        HorizontalLayout header = new HorizontalLayout(new H2("Parser confidence"), confidence, copy);
        header.setWidthFull();
        header.expand(header.getComponentAt(0));
        header.setAlignItems(HorizontalLayout.Alignment.CENTER);
        header.addClassName("parser-header");

        add(eyebrow, header, help, coverageLabel, coverage, metrics, ignoredDetails);
        addClassNames("panel", "parser-confidence-panel");
    }

    private Div metric(String label, int value) {
        Div metric = new Div(new Span(Integer.toString(value)), new Span(label));
        metric.addClassName("parser-metric");
        return metric;
    }

    private void copyDiagnostics() {
        String samples = diagnostics.ignoredLineSamples().stream()
                .map(line -> line.occurrences() + "× " + line.text())
                .collect(Collectors.joining(System.lineSeparator()));
        String text = "ThreadCity parser diagnostics" + System.lineSeparator()
                + "Coverage: " + diagnostics.coveragePercent() + "%" + System.lineSeparator()
                + "Meaningful lines: " + diagnostics.contentLines() + System.lineSeparator()
                + "Recognized: " + diagnostics.recognizedLines() + System.lineSeparator()
                + "Ignored: " + diagnostics.ignoredLines() + System.lineSeparator()
                + samples;
        getElement().executeJs("navigator.clipboard.writeText($0)", text);
        successNotifier.accept("Parser diagnostics copied");
    }
}
