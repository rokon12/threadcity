package ca.bazlur.threadcity.ui.component;

import ca.bazlur.threadcity.domain.AnalysisResult;
import ca.bazlur.threadcity.domain.JavaThread;
import ca.bazlur.threadcity.domain.ThreadState;
import ca.bazlur.threadcity.domain.ThreadMetadata;
import ca.bazlur.threadcity.ui.support.IncidentNarrative;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.grid.contextmenu.GridContextMenu;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Pre;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.masterdetaillayout.MasterDetailLayout;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.value.ValueChangeMode;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Owns thread filtering, selection, and the responsive stack inspector.
 */
public final class ThreadEvidencePanel extends Div {

    private final Consumer<String> successNotifier;
    private final Consumer<JavaThread> askAi;
    private final MasterDetailLayout workbench = new MasterDetailLayout();
    private final Grid<JavaThread> grid = new Grid<>();
    private final Div stackPanel = new Div();
    private final Pre stackTrace = new Pre("Select a thread to inspect its stack.");
    private final TextField search = new TextField("Search threads or frames");
    private final ComboBox<ThreadState> stateFilter = new ComboBox<>("State");
    private final ComboBox<ThreadMetadata.ThreadKind> kindFilter = new ComboBox<>("Kind");
    private final Checkbox deadlockOnly = new Checkbox("Confirmed deadlock only");
    private final Span filterSummary = new Span("No snapshot loaded");
    private final Button askSelectedThread = new Button("✦ Ask AI about thread");
    private final Div threadTelemetry = new Div();

    private AnalysisResult result;
    private Set<Integer> deadlockedThreadIds = Set.of();
    private Set<Integer> focusedThreadIds = Set.of();
    private boolean updatingFilters;

    public ThreadEvidencePanel(Consumer<String> successNotifier, Consumer<JavaThread> askAi) {
        this.successNotifier = successNotifier;
        this.askAi = askAi;
        configureFilters();
        configureGrid();
        buildLayout();
    }

    public void showResult(AnalysisResult result) {
        this.result = result;
        deadlockedThreadIds = result.deadlocks().stream()
                .flatMap(cycle -> cycle.threads().stream())
                .map(JavaThread::id)
                .collect(Collectors.toUnmodifiableSet());
        resetFilters();
        grid.deselectAll();
        workbench.setDetail(null);
        stackTrace.setText("Select a thread to inspect its stack.");
        threadTelemetry.removeAll();
    }

    public void clear() {
        result = null;
        deadlockedThreadIds = Set.of();
        focusedThreadIds = Set.of();
        resetFilterControls();
        grid.deselectAll();
        grid.setItems(List.of());
        stackTrace.setText("Select a thread to inspect its stack.");
        threadTelemetry.removeAll();
        workbench.setDetail(null);
        filterSummary.setText("No snapshot loaded");
    }

    public void inspectThread(String threadName) {
        if (result == null) {
            return;
        }
        result.snapshot().threads().stream()
                .filter(thread -> thread.name().equals(threadName))
                .findFirst()
                .ifPresent(thread -> {
                    focusedThreadIds = Set.of();
                    resetFilterControls();
                    updateGridItems();
                    select(thread);
                });
    }

    public void focusThreads(Collection<String> threadNames) {
        if (result == null) {
            return;
        }
        Set<String> names = Set.copyOf(threadNames);
        focusedThreadIds = result.snapshot().threads().stream()
                .filter(thread -> names.contains(thread.name()))
                .map(JavaThread::id)
                .collect(Collectors.toUnmodifiableSet());
        resetFilterControls();
        updateGridItems();
        result.snapshot().threads().stream()
                .filter(thread -> focusedThreadIds.contains(thread.id()))
                .findFirst()
                .ifPresent(this::select);
    }

    public void updateResponsiveMode(int viewportWidth) {
        workbench.setForceOverlay(viewportWidth < 1_100);
        workbench.setOverlaySize(viewportWidth < 620 ? "100%" : "min(100%, 580px)");
    }

    private void configureFilters() {
        search.setPlaceholder("checkout, BLOCKED, example.Service...");
        search.setClearButtonVisible(true);
        search.setValueChangeMode(ValueChangeMode.EAGER);
        search.addValueChangeListener(event -> filterChanged());

        stateFilter.setItems(ThreadState.values());
        stateFilter.setClearButtonVisible(true);
        stateFilter.setPlaceholder("All states");
        stateFilter.addValueChangeListener(event -> filterChanged());
        kindFilter.setItems(ThreadMetadata.ThreadKind.values());
        kindFilter.setItemLabelGenerator(ThreadMetadata.ThreadKind::label);
        kindFilter.setClearButtonVisible(true);
        kindFilter.setPlaceholder("All kinds");
        kindFilter.addValueChangeListener(event -> filterChanged());
        deadlockOnly.addValueChangeListener(event -> filterChanged());
    }

    private void filterChanged() {
        if (!updatingFilters) {
            focusedThreadIds = Set.of();
            updateGridItems();
        }
    }

    private void configureGrid() {
        grid.addClassName("thread-grid");
        grid.addThemeVariants(GridVariant.LUMO_NO_BORDER, GridVariant.LUMO_ROW_STRIPES);
        grid.addColumn(JavaThread::name).setHeader("Thread").setFlexGrow(2).setSortable(true);
        grid.addColumn(thread -> thread.state().name()).setHeader("State").setAutoWidth(true).setSortable(true);
        grid.addColumn(thread -> thread.metadata().kind().label())
                .setHeader("Kind").setAutoWidth(true).setSortable(true);
        grid.addColumn(thread -> thread.metadata().cpuDisplay())
                .setHeader("CPU").setAutoWidth(true).setSortable(true);
        grid.addColumn(thread -> thread.metadata().elapsedDisplay())
                .setHeader("Elapsed").setAutoWidth(true).setSortable(true);
        grid.addColumn(thread -> thread.waitingOn() == null ? "—" : thread.waitingOn().shortId())
                .setHeader("Waiting for").setAutoWidth(true);
        grid.addColumn(thread -> thread.ownedLocks().size()).setHeader("Owns").setAutoWidth(true);
        grid.addColumn(JavaThread::topFrame).setHeader("Top frame").setFlexGrow(3);
        grid.setHeight("390px");
        grid.asSingleSelect().addValueChangeListener(event -> {
            JavaThread selected = event.getValue();
            stackTrace.setText(selected == null
                    ? "Select a thread to inspect its stack."
                    : IncidentNarrative.threadDetails(selected));
            if (selected != null) {
                renderTelemetry(selected);
                workbench.setDetail(stackPanel);
                workbench.getElement().callJsFunction("scrollIntoView", true);
            }
            askSelectedThread.setEnabled(selected != null);
        });

        GridContextMenu<JavaThread> contextMenu = grid.addContextMenu();
        contextMenu.addItem("Inspect stack", event -> event.getItem().ifPresent(this::select));
        contextMenu.addItem("Copy thread name", event -> event.getItem().ifPresent(thread -> {
            getElement().executeJs("navigator.clipboard.writeText($0)", thread.name());
            successNotifier.accept("Thread name copied");
        }));
        contextMenu.addItem("Copy JVM identity", event -> event.getItem().ifPresent(thread -> {
            String identity = thread.name() + " · tid=" + value(thread.metadata().tid())
                    + " · nid=" + value(thread.metadata().nid())
                    + " · Java #=" + value(thread.metadata().javaThreadNumber());
            getElement().executeJs("navigator.clipboard.writeText($0)", identity);
            successNotifier.accept("JVM thread identity copied");
        }));
        if (askAi != null) {
            contextMenu.addItem("✦ Ask AI about this thread", event -> event.getItem().ifPresent(askAi));
        }
    }

    private void buildLayout() {
        HorizontalLayout filterBar = new HorizontalLayout(search, stateFilter, kindFilter, deadlockOnly);
        filterBar.addClassName("filter-bar");
        filterBar.setAlignItems(HorizontalLayout.Alignment.END);
        filterBar.setWidthFull();
        search.setWidthFull();
        filterBar.expand(search);

        Button clearFilters = new Button("Reset filters", event -> resetFilters());
        clearFilters.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE);
        HorizontalLayout filterFooter = new HorizontalLayout(filterSummary, clearFilters);
        filterFooter.addClassName("filter-footer");
        filterFooter.setWidthFull();
        filterFooter.expand(filterSummary);

        Div gridPanel = new Div();
        gridPanel.addClassName("panel");
        Paragraph gridHelp = new Paragraph("Filter the snapshot or select any map node or row to inspect its stack.");
        gridHelp.addClassName("panel-help");
        gridPanel.add(new H2("Thread inventory"), gridHelp, filterBar, filterFooter, grid);

        stackPanel.addClassNames("panel", "stack-panel");
        askSelectedThread.addClickListener(event -> {
            JavaThread selected = grid.asSingleSelect().getValue();
            if (selected != null && askAi != null) {
                askAi.accept(selected);
            }
        });
        askSelectedThread.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        askSelectedThread.setEnabled(false);
        askSelectedThread.setVisible(askAi != null);
        Button closeInspector = new Button("Close inspector", VaadinIcon.CLOSE_SMALL.create(), event -> {
            workbench.setDetail(null);
            grid.deselectAll();
        });
        closeInspector.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE);
        HorizontalLayout stackHeader = new HorizontalLayout(
                new H2("Stack inspector"), askSelectedThread, closeInspector);
        stackHeader.addClassName("stack-header");
        stackHeader.setWidthFull();
        stackHeader.expand(stackHeader.getComponentAt(0));
        threadTelemetry.addClassName("thread-telemetry");
        stackPanel.add(stackHeader, threadTelemetry, stackTrace);

        workbench.addClassName("thread-workbench");
        workbench.setMaster(gridPanel);
        Div placeholder = new Div(
                VaadinIcon.CURSOR_O.create(),
                new H2("Select a thread"),
                new Paragraph("The evidence inspector opens here and becomes a full overlay on smaller screens."));
        placeholder.addClassName("inspector-placeholder");
        workbench.setDetailPlaceholder(placeholder);
        workbench.setMasterSize("66%");
        workbench.setDetailSize("34%");
        workbench.setExpandMaster(true);
        workbench.setOverlaySize("min(100%, 580px)");
        workbench.setAnimationEnabled(true);
        add(workbench);
    }

    private void resetFilters() {
        focusedThreadIds = Set.of();
        resetFilterControls();
        updateGridItems();
    }

    private void resetFilterControls() {
        updatingFilters = true;
        try {
            search.clear();
            stateFilter.clear();
            kindFilter.clear();
            deadlockOnly.setValue(false);
        } finally {
            updatingFilters = false;
        }
    }

    private void updateGridItems() {
        if (result == null) {
            grid.setItems(List.of());
            filterSummary.setText("No snapshot loaded");
            return;
        }
        String query = search.getValue() == null ? "" : search.getValue().strip().toLowerCase(Locale.ROOT);
        List<JavaThread> visible = result.snapshot().threads().stream()
                .filter(thread -> focusedThreadIds.isEmpty() || focusedThreadIds.contains(thread.id()))
                .filter(thread -> !deadlockOnly.getValue() || deadlockedThreadIds.contains(thread.id()))
                .filter(thread -> stateFilter.getValue() == null || thread.state() == stateFilter.getValue())
                .filter(thread -> kindFilter.getValue() == null
                        || thread.metadata().kind() == kindFilter.getValue())
                .filter(thread -> query.isEmpty()
                        || thread.name().toLowerCase(Locale.ROOT).contains(query)
                        || thread.metadata().searchText().contains(query)
                        || thread.stackFrames().stream()
                                .anyMatch(frame -> frame.toLowerCase(Locale.ROOT).contains(query)))
                .sorted(Comparator.comparingInt(JavaThread::id))
                .toList();
        grid.setItems(visible);
        String focus = focusedThreadIds.isEmpty() ? "" : " · finding focus";
        filterSummary.setText(visible.size() + " of " + result.snapshot().threads().size() + " threads" + focus);
        if (visible.isEmpty()) {
            grid.deselectAll();
            stackTrace.setText("No threads match the current filters.");
        }
    }

    private void select(JavaThread thread) {
        grid.select(thread);
        grid.scrollToItem(thread);
        stackTrace.setText(IncidentNarrative.threadDetails(thread));
        renderTelemetry(thread);
        workbench.setDetail(stackPanel);
        workbench.getElement().callJsFunction("scrollIntoView", true);
    }

    private void renderTelemetry(JavaThread thread) {
        ThreadMetadata metadata = thread.metadata();
        threadTelemetry.removeAll();
        threadTelemetry.add(
                telemetry("Kind", metadata.kind().label()),
                telemetry("Java #", value(metadata.javaThreadNumber())),
                telemetry("tid", value(metadata.tid())),
                telemetry("nid", value(metadata.nid())),
                telemetry("CPU", metadata.cpuDisplay()),
                telemetry("Elapsed", metadata.elapsedDisplay()),
                telemetry("Priority", value(metadata.priority())),
                telemetry("Daemon", metadata.daemon() ? "Yes" : "No"));
    }

    private Div telemetry(String label, String value) {
        Div item = new Div(new Span(value), new Span(label));
        item.addClassName("thread-telemetry-item");
        return item;
    }

    private String value(Object value) {
        return value == null ? "—" : value.toString();
    }
}
