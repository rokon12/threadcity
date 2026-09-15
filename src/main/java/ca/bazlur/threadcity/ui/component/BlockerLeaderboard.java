package ca.bazlur.threadcity.ui.component;

import ca.bazlur.threadcity.domain.AnalysisResult;
import ca.bazlur.threadcity.domain.BlockingImpact;
import ca.bazlur.threadcity.domain.JavaThread;
import ca.bazlur.threadcity.domain.WaitEdge;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.treegrid.TreeGrid;
import com.vaadin.flow.data.renderer.ComponentRenderer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Expandable ranking of lock owners by their direct and transitive blast radius.
 */
public final class BlockerLeaderboard extends Div {

    private static final int MAX_ROOTS = 50;
    private static final int MAX_TREE_NODES_PER_ROOT = 200;
    private final Consumer<JavaThread> inspectThread;
    private final TreeGrid<BlockerRow> grid = new TreeGrid<>();
    private final Paragraph summary = new Paragraph();
    private final Div emptyState = new Div(new Span("✓"), new Span("No ownership-dependent blockers"));

    public BlockerLeaderboard(Consumer<JavaThread> inspectThread) {
        this.inspectThread = inspectThread;
        configureGrid();
        emptyState.addClassName("blocker-empty");
        summary.addClassName("panel-help");
        add(new H2("Blocker leaderboard"), summary, grid, emptyState);
        addClassNames("panel", "blocker-leaderboard");
    }

    public void render(AnalysisResult result) {
        if (result.blockingImpacts().isEmpty()) {
            summary.setText("No thread currently owns a lock required by another thread.");
            grid.setVisible(false);
            emptyState.setVisible(true);
            return;
        }

        List<BlockerRow> rows = buildRows(result);
        grid.setItems(rows, BlockerRow::children);
        grid.setVisible(true);
        emptyState.setVisible(false);
        BlockingImpact leader = result.blockingImpacts().getFirst();
        summary.setText("Ranked by distinct downstream threads. " + leader.blocker().name()
                + " has the largest blast radius: " + leader.transitivelyBlocked()
                + " thread" + plural(leader.transitivelyBlocked()) + " across "
                + leader.maximumDepth() + " level" + plural(leader.maximumDepth()) + ".");
    }

    private void configureGrid() {
        grid.addClassName("blocker-grid");
        grid.addHierarchyColumn(row -> row.thread().name()).setHeader("Blocking chain").setFlexGrow(2);
        grid.addColumn(new ComponentRenderer<>(row -> impactBadge(row.downstream())))
                .setHeader("Blast radius").setAutoWidth(true);
        grid.addColumn(row -> row.root() ? row.directlyBlocked() : "—")
                .setHeader("Direct").setAutoWidth(true);
        grid.addColumn(row -> row.thread().state().name()).setHeader("State").setAutoWidth(true);
        grid.addColumn(BlockerRow::relationship).setHeader("Dependency").setFlexGrow(2);
        grid.addColumn(BlockerRow::lockId).setHeader("Via lock").setAutoWidth(true);
        grid.setHeight("330px");
        grid.asSingleSelect().addValueChangeListener(event -> {
            BlockerRow row = event.getValue();
            if (row != null) {
                inspectThread.accept(row.thread());
            }
        });
    }

    private Span impactBadge(int downstream) {
        Span badge = new Span(Integer.toString(downstream));
        badge.addClassNames("blast-radius", downstream >= 3 ? "blast-radius-critical" : "blast-radius-warning");
        badge.getElement().setAttribute("aria-label", downstream + " downstream threads");
        return badge;
    }

    private List<BlockerRow> buildRows(AnalysisResult result) {
        Map<Integer, BlockingImpact> impacts = new LinkedHashMap<>();
        result.blockingImpacts().forEach(impact -> impacts.put(impact.blocker().id(), impact));
        Map<Integer, List<WaitEdge>> incoming = new LinkedHashMap<>();
        result.waitEdges().forEach(edge -> incoming
                .computeIfAbsent(edge.owner().id(), ignored -> new ArrayList<>())
                .add(edge));

        return result.blockingImpacts().stream()
                .limit(MAX_ROOTS)
                .map(impact -> new BlockerRow(
                        impact.blocker(),
                        impact.directlyBlocked(),
                        impact.transitivelyBlocked(),
                        "Owns locks needed downstream",
                        "—",
                        true,
                        childrenOf(
                                impact.blocker(),
                                incoming,
                                impacts,
                                new HashSet<>(Set.of(impact.blocker().id())),
                                new int[]{1})))
                .toList();
    }

    private List<BlockerRow> childrenOf(
            JavaThread owner,
            Map<Integer, List<WaitEdge>> incoming,
            Map<Integer, BlockingImpact> impacts,
            Set<Integer> path,
            int[] emittedNodes) {
        return incoming.getOrDefault(owner.id(), List.of()).stream()
                .takeWhile(ignored -> emittedNodes[0] < MAX_TREE_NODES_PER_ROOT)
                .map(edge -> {
                    emittedNodes[0]++;
                    JavaThread waiter = edge.waiter();
                    BlockingImpact impact = impacts.get(waiter.id());
                    boolean closesCycle = path.contains(waiter.id());
                    Set<Integer> nextPath = new HashSet<>(path);
                    nextPath.add(waiter.id());
                    return new BlockerRow(
                            waiter,
                            0,
                            impact == null ? 0 : impact.transitivelyBlocked(),
                            closesCycle ? "Cycle closes here" : "Waits for " + owner.name(),
                            edge.lock().shortId(),
                            false,
                            closesCycle || emittedNodes[0] >= MAX_TREE_NODES_PER_ROOT
                                    ? List.of()
                                    : childrenOf(waiter, incoming, impacts, nextPath, emittedNodes));
                })
                .toList();
    }

    private String plural(int count) {
        return count == 1 ? "" : "s";
    }

    private record BlockerRow(
            JavaThread thread,
            int directlyBlocked,
            int downstream,
            String relationship,
            String lockId,
            boolean root,
            List<BlockerRow> children) {
    }
}
