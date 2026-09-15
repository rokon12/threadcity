package ca.bazlur.threadcity.ui.component;

import ca.bazlur.threadcity.domain.AnalysisResult;
import ca.bazlur.threadcity.domain.JavaThread;
import ca.bazlur.threadcity.domain.SynchronizerInsight;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.details.Details;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.masterdetaillayout.MasterDetailLayout;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.data.renderer.ComponentRenderer;

import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Cross-referenced inventory of every lock, owner, waiter, and deterministic risk classification.
 */
public final class SynchronizerObservatory extends Div {

    private final Consumer<JavaThread> inspectThread;
    private final Consumer<String> highlightLock;
    private final MasterDetailLayout workbench = new MasterDetailLayout();
    private final Grid<SynchronizerInsight> grid = new Grid<>();
    private final Span summary = new Span("No snapshot loaded");

    public SynchronizerObservatory(Consumer<JavaThread> inspectThread, Consumer<String> highlightLock) {
        this.inspectThread = inspectThread;
        this.highlightLock = highlightLock;
        configureGrid();
        buildLayout();
    }

    public void render(AnalysisResult result) {
        grid.setItems(result.synchronizers());
        grid.deselectAll();
        workbench.setDetail(null);
        long dangerous = result.synchronizers().stream()
                .filter(insight -> insight.risk() == SynchronizerInsight.Risk.DEADLOCKED
                        || insight.risk() == SynchronizerInsight.Risk.CONTENDED)
                .count();
        summary.setText(result.synchronizers().size() + " synchronizer"
                + plural(result.synchronizers().size()) + " observed · " + dangerous + " require attention");
    }

    public void clear() {
        grid.setItems(List.of());
        grid.deselectAll();
        workbench.setDetail(null);
        summary.setText("No snapshot loaded");
    }

    private void configureGrid() {
        grid.addClassName("synchronizer-grid");
        grid.addThemeVariants(GridVariant.LUMO_NO_BORDER, GridVariant.LUMO_ROW_STRIPES);
        grid.addColumn(new ComponentRenderer<>(insight -> riskBadge(insight.risk())))
                .setHeader("Risk").setAutoWidth(true);
        grid.addColumn(insight -> insight.lock().shortId()).setHeader("Lock").setAutoWidth(true).setSortable(true);
        grid.addColumn(insight -> insight.lock().className()).setHeader("Class").setFlexGrow(3).setSortable(true);
        grid.addColumn(insight -> names(insight.owners())).setHeader("Owner").setFlexGrow(2);
        grid.addColumn(insight -> insight.acquisitionWaiters().size()).setHeader("Acquire").setAutoWidth(true);
        grid.addColumn(insight -> insight.notificationWaiters().size()).setHeader("Notify").setAutoWidth(true);
        grid.addColumn(SynchronizerInsight::downstreamImpact).setHeader("Impact").setAutoWidth(true);
        grid.setHeight("480px");
        grid.asSingleSelect().addValueChangeListener(event -> {
            if (event.getValue() != null) {
                workbench.setDetail(detail(event.getValue()));
            }
        });
    }

    private void buildLayout() {
        Div master = new Div(
                new H2("Synchronizer Observatory"),
                new Paragraph("Inspect every owned or requested lock, including waits that cannot safely become graph edges."),
                summary,
                grid);
        master.addClassNames("panel", "synchronizer-master");
        master.getChildren().filter(Paragraph.class::isInstance)
                .forEach(component -> component.addClassName("panel-help"));
        summary.addClassName("synchronizer-summary");

        Div placeholder = new Div(
                new H2("Select a synchronizer"),
                new Paragraph("Owners, waiter types, risk evidence, and map navigation will appear here."));
        placeholder.addClassName("inspector-placeholder");

        workbench.setMaster(master);
        workbench.setDetailPlaceholder(placeholder);
        workbench.setMasterSize("68%");
        workbench.setDetailSize("32%");
        workbench.setExpandMaster(true);
        workbench.setOverlaySize("min(100%, 620px)");
        workbench.setAnimationEnabled(true);
        workbench.addClassName("synchronizer-workbench");
        add(workbench);
    }

    private Div detail(SynchronizerInsight insight) {
        Div detail = new Div();
        detail.addClassNames("panel", "synchronizer-detail");
        Span risk = riskBadge(insight.risk());
        H2 title = new H2(insight.lock().shortId());
        Paragraph className = new Paragraph(insight.lock().className());
        className.addClassName("lock-class-name");

        Div metrics = new Div(
                metric("Owners", insight.owners().size()),
                metric("Acquire waiters", insight.acquisitionWaiters().size()),
                metric("Notify waiters", insight.notificationWaiters().size()),
                metric("Blast radius", insight.downstreamImpact()));
        metrics.addClassName("synchronizer-metrics");

        Details owners = people("Owners", insight.owners());
        Details acquisition = people("Waiting to acquire", insight.acquisitionWaiters());
        Details notification = people("Awaiting notification", insight.notificationWaiters());
        owners.setOpened(true);
        acquisition.setOpened(!insight.acquisitionWaiters().isEmpty());

        Button map = new Button("Locate on traffic map", event -> highlightLock.accept(insight.lock().shortId()));
        map.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        Button close = new Button("Close", event -> {
            workbench.setDetail(null);
            grid.deselectAll();
        });
        close.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        HorizontalLayout actions = new HorizontalLayout(map, close);
        actions.addClassName("synchronizer-actions");

        detail.add(risk, title, className, metrics, owners, acquisition, notification, actions);
        return detail;
    }

    private Details people(String label, List<JavaThread> threads) {
        Div content = new Div();
        content.addClassName("synchronizer-people");
        if (threads.isEmpty()) {
            content.add(new Span("None recorded"));
        } else {
            threads.forEach(thread -> {
                Button inspect = new Button(thread.name(), event -> inspectThread.accept(thread));
                inspect.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE);
                Span state = new Span(thread.state().name());
                Div row = new Div(inspect, state);
                row.addClassName("synchronizer-person");
                content.add(row);
            });
        }
        return new Details(label + " · " + threads.size(), content);
    }

    private Component metric(String label, int value) {
        Div metric = new Div(new Span(Integer.toString(value)), new Span(label));
        metric.addClassName("synchronizer-metric");
        return metric;
    }

    private Span riskBadge(SynchronizerInsight.Risk risk) {
        Span badge = new Span(risk.label());
        badge.addClassNames("sync-risk", "sync-risk-" + risk.name().toLowerCase());
        return badge;
    }

    private String names(List<JavaThread> threads) {
        return threads.isEmpty()
                ? "Unresolved"
                : threads.stream().map(JavaThread::name).collect(Collectors.joining(", "));
    }

    private String plural(int count) {
        return count == 1 ? "" : "s";
    }
}
