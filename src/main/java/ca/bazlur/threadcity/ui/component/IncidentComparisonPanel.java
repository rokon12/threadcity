package ca.bazlur.threadcity.ui.component;

import ca.bazlur.threadcity.application.ThreadDumpAnalysisService;
import ca.bazlur.threadcity.domain.AnalysisResult;
import ca.bazlur.threadcity.domain.ThreadState;
import ca.bazlur.threadcity.ui.support.IncidentNarrative;
import com.vaadin.flow.component.details.Details;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.splitlayout.SplitLayout;

/**
 * Side-by-side explanation of the broken and corrected lock ordering.
 */
public final class IncidentComparisonPanel extends Div {

    public IncidentComparisonPanel(ThreadDumpAnalysisService analysisService) {
        AnalysisResult before = analysisService.analyzeSample("Before · circular wait", "deadlock.txt");
        AnalysisResult after = analysisService.analyzeSample("After · consistent lock order", "corrected.txt");

        SplitLayout split = new SplitLayout(
                snapshot("BEFORE", before, "compare-before"),
                snapshot("AFTER", after, "compare-after"));
        split.addClassName("comparison-split");
        split.setWidthFull();
        split.setSplitterPosition(50);

        Div changes = new Div(
                new Paragraph("✓ PaymentLock is acquired before InventoryLock by both operations."),
                new Paragraph("✓ Two circular wait edges are eliminated."),
                new Paragraph("✓ Blocked checkout traffic returns to a progress-capable state."));
        changes.addClassName("comparison-changes");
        Details details = new Details("What changed in the fix", changes);
        details.setOpened(true);

        add(new H2("Broken vs fixed"),
                new Paragraph("Drag the Vaadin Split Layout divider to compare the same workload before and after the fix."),
                split, details);
        addClassNames("panel", "comparison-panel");
    }

    private Div snapshot(String label, AnalysisResult result, String tone) {
        Span badge = new Span(label);
        badge.addClassName("comparison-label");
        Div counts = new Div(
                metric("Blocked", result.stateCounts().get(ThreadState.BLOCKED)),
                metric("Wait edges", result.waitEdges().size()),
                metric("Deadlocks", result.deadlocks().size()));
        counts.addClassName("comparison-metrics");
        Div panel = new Div(
                badge,
                new H2(result.hasDeadlock() ? "Circular wait" : "Traffic flowing"),
                new Paragraph(IncidentNarrative.diagnosis(result)),
                counts);
        panel.addClassNames("comparison-snapshot", tone);
        return panel;
    }

    private Div metric(String label, long value) {
        Div metric = new Div(new Span(Long.toString(value)), new Span(label));
        metric.addClassName("comparison-metric");
        return metric;
    }
}
