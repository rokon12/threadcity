package ca.bazlur.threadcity.ui.component;

import ca.bazlur.threadcity.domain.AnalysisResult;
import ca.bazlur.threadcity.domain.JavaThread;
import ca.bazlur.threadcity.domain.MethodHotspot;
import ca.bazlur.threadcity.domain.StackCohort;
import ca.bazlur.threadcity.domain.ThreadState;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.combobox.MultiSelectComboBox;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.progressbar.ProgressBar;
import com.vaadin.flow.component.tabs.Tab;
import com.vaadin.flow.component.tabs.Tabs;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.component.treegrid.TreeGrid;
import com.vaadin.flow.data.renderer.ComponentRenderer;
import com.vaadin.flow.data.value.ValueChangeMode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Explores exact stack fingerprints and runnable top-frame hotspots.
 */
public final class StackCohortExplorer extends Div {

    private final Consumer<JavaThread> inspectThread;
    private final TextField search = new TextField("Search cohorts");
    private final MultiSelectComboBox<ThreadState> states = new MultiSelectComboBox<>("Thread states");
    private final Checkbox repeatedOnly = new Checkbox("Repeated stacks only");
    private final Span summary = new Span("No snapshot loaded");
    private final TreeGrid<CohortRow> cohortGrid = new TreeGrid<>();
    private final Grid<MethodHotspot> methodGrid = new Grid<>();
    private final Tabs viewTabs = new Tabs();
    private final Div cohortPage = new Div();
    private final Div methodPage = new Div();
    private AnalysisResult result;

    public StackCohortExplorer(Consumer<JavaThread> inspectThread) {
        this.inspectThread = inspectThread;
        configureFilters();
        configureCohortGrid();
        configureMethodGrid();
        buildLayout();
    }

    public void render(AnalysisResult result) {
        this.result = result;
        search.clear();
        states.clear();
        repeatedOnly.setValue(false);
        updateItems();
    }

    public void clear() {
        result = null;
        search.clear();
        states.clear();
        repeatedOnly.setValue(false);
        cohortGrid.setItems(List.of(), CohortRow::children);
        methodGrid.setItems(List.of());
        summary.setText("No snapshot loaded");
    }

    private void configureFilters() {
        search.setPlaceholder("fingerprint, frame, or thread name");
        search.setClearButtonVisible(true);
        search.setValueChangeMode(ValueChangeMode.EAGER);
        search.addValueChangeListener(event -> updateItems());
        search.setWidthFull();
        states.setItems(ThreadState.values());
        states.setItemLabelGenerator(ThreadState::name);
        states.setClearButtonVisible(true);
        states.setPlaceholder("All states");
        states.addValueChangeListener(event -> updateItems());
        repeatedOnly.addValueChangeListener(event -> updateItems());
    }

    private void configureCohortGrid() {
        cohortGrid.addClassName("cohort-tree-grid");
        cohortGrid.addHierarchyColumn(CohortRow::label).setHeader("Stack fingerprint / thread").setFlexGrow(2);
        cohortGrid.addColumn(CohortRow::count).setHeader("Threads").setAutoWidth(true);
        cohortGrid.addColumn(CohortRow::states).setHeader("State mix").setFlexGrow(2);
        cohortGrid.addColumn(CohortRow::topFrame).setHeader("Top frame").setFlexGrow(3);
        cohortGrid.setHeight("470px");
        cohortGrid.asSingleSelect().addValueChangeListener(event -> {
            CohortRow row = event.getValue();
            if (row != null && row.thread() != null) {
                inspectThread.accept(row.thread());
            }
        });
    }

    private void configureMethodGrid() {
        methodGrid.addClassName("method-hotspot-grid");
        methodGrid.addThemeVariants(GridVariant.LUMO_NO_BORDER, GridVariant.LUMO_ROW_STRIPES);
        methodGrid.addColumn(MethodHotspot::method).setHeader("Running top frame").setFlexGrow(4);
        methodGrid.addColumn(hotspot -> hotspot.threads().size()).setHeader("Threads").setAutoWidth(true);
        methodGrid.addColumn(new ComponentRenderer<>(this::shareBar)).setHeader("Share of runnable").setFlexGrow(2);
        methodGrid.setHeight("420px");
        methodGrid.asSingleSelect().addValueChangeListener(event -> {
            MethodHotspot hotspot = event.getValue();
            if (hotspot != null) {
                inspectThread.accept(hotspot.threads().getFirst());
            }
        });
    }

    private void buildLayout() {
        Span eyebrow = new Span("STACK FINGERPRINT INDEX");
        eyebrow.addClassName("eyebrow");
        Paragraph help = new Paragraph(
                "Identical ordered frames become one cohort. Expand a fingerprint to inspect every matching thread.");
        help.addClassName("panel-help");

        HorizontalLayout filters = new HorizontalLayout(search, states, repeatedOnly);
        filters.setAlignItems(HorizontalLayout.Alignment.END);
        filters.setWidthFull();
        filters.expand(search);
        filters.addClassName("cohort-filter-bar");
        summary.addClassName("cohort-summary");

        Tab cohorts = new Tab("Stack cohorts");
        Tab methods = new Tab("Running methods");
        viewTabs.add(cohorts, methods);
        viewTabs.addClassName("cohort-view-tabs");
        viewTabs.addSelectedChangeListener(event -> {
            cohortPage.setVisible(event.getSelectedTab() == cohorts);
            methodPage.setVisible(event.getSelectedTab() == methods);
        });
        cohortPage.add(cohortGrid);
        methodPage.add(
                new Paragraph("Runnable threads grouped by their current top Java frame."),
                methodGrid);
        methodPage.getChildren().filter(Paragraph.class::isInstance)
                .forEach(component -> component.addClassName("panel-help"));
        methodPage.setVisible(false);

        add(eyebrow, new H2("Stack Cohort Explorer"), help, filters, summary, viewTabs, cohortPage, methodPage);
        addClassNames("panel", "stack-cohort-explorer");
    }

    private void updateItems() {
        if (result == null) {
            return;
        }
        String query = search.getValue() == null ? "" : search.getValue().strip().toLowerCase(Locale.ROOT);
        Set<ThreadState> selectedStates = states.getValue();
        List<CohortRow> rows = new ArrayList<>();
        for (StackCohort cohort : result.stackCohorts()) {
            if (repeatedOnly.getValue() && !cohort.repeated()) {
                continue;
            }
            boolean cohortTextMatch = query.isEmpty()
                    || cohort.fingerprint().contains(query)
                    || cohort.stackFrames().stream().anyMatch(frame -> frame.toLowerCase(Locale.ROOT).contains(query));
            List<JavaThread> visibleThreads = cohort.threads().stream()
                    .filter(thread -> selectedStates.isEmpty() || selectedStates.contains(thread.state()))
                    .filter(thread -> cohortTextMatch || thread.name().toLowerCase(Locale.ROOT).contains(query))
                    .toList();
            if (!visibleThreads.isEmpty()) {
                rows.add(CohortRow.cohort(cohort, visibleThreads));
            }
        }
        cohortGrid.setItems(rows, CohortRow::children);

        List<MethodHotspot> methods = result.methodHotspots().stream()
                .filter(hotspot -> query.isEmpty()
                        || hotspot.method().toLowerCase(Locale.ROOT).contains(query)
                        || hotspot.threads().stream()
                                .anyMatch(thread -> thread.name().toLowerCase(Locale.ROOT).contains(query)))
                .toList();
        methodGrid.setItems(methods);
        long representedThreads = rows.stream().mapToLong(CohortRow::count).sum();
        summary.setText(rows.size() + " cohort" + plural(rows.size()) + " · " + representedThreads
                + " represented thread" + plural(representedThreads) + " · "
                + result.methodHotspots().size() + " running method" + plural(result.methodHotspots().size()));
    }

    private Component shareBar(MethodHotspot hotspot) {
        int runnable = Math.max(1, result == null
                ? hotspot.threads().size()
                : result.methodHotspots().stream().mapToInt(item -> item.threads().size()).sum());
        ProgressBar progress = new ProgressBar(0, runnable, hotspot.threads().size());
        progress.setWidthFull();
        progress.getElement().setAttribute("aria-label",
                hotspot.threads().size() + " of " + runnable + " runnable threads");
        return progress;
    }

    private String plural(long count) {
        return count == 1 ? "" : "s";
    }

    private record CohortRow(
            String label,
            int count,
            String states,
            String topFrame,
            JavaThread thread,
            List<CohortRow> children) {

        private static CohortRow cohort(StackCohort cohort, List<JavaThread> visibleThreads) {
            String stateMix = visibleThreads.stream()
                    .collect(Collectors.groupingBy(
                            JavaThread::state,
                            java.util.LinkedHashMap::new,
                            Collectors.counting()))
                    .entrySet().stream()
                    .sorted(MapEntryComparator.INSTANCE)
                    .map(entry -> entry.getKey().name() + " " + entry.getValue())
                    .collect(Collectors.joining(" · "));
            List<CohortRow> children = visibleThreads.stream()
                    .sorted(Comparator.comparing(JavaThread::name).thenComparingInt(JavaThread::id))
                    .map(item -> new CohortRow(
                            item.name(), 1, item.state().name(), item.topFrame(), item, List.of()))
                    .toList();
            return new CohortRow(
                    cohort.fingerprint(), visibleThreads.size(), stateMix, cohort.topFrame(), null, children);
        }
    }

    private enum MapEntryComparator implements Comparator<java.util.Map.Entry<ThreadState, Long>> {
        INSTANCE;

        @Override
        public int compare(java.util.Map.Entry<ThreadState, Long> left,
                           java.util.Map.Entry<ThreadState, Long> right) {
            return left.getKey().compareTo(right.getKey());
        }
    }
}
