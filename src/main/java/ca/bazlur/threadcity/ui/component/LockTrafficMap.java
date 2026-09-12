package ca.bazlur.threadcity.ui.component;

import ca.bazlur.threadcity.domain.AnalysisResult;
import ca.bazlur.threadcity.domain.JavaThread;
import ca.bazlur.threadcity.domain.WaitEdge;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.dom.Element;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Renders the waiter-to-lock-owner topology and delegates thread inspection.
 */
public final class LockTrafficMap extends Div {

    private final Consumer<String> inspectThread;
    private final Consumer<WaitEdge> askAi;
    private final boolean compact;
    private AnalysisResult result;
    private String highlightedLockId;

    public LockTrafficMap(String className, boolean compact, Consumer<String> inspectThread) {
        this(className, compact, inspectThread, null);
    }

    public LockTrafficMap(
            String className,
            boolean compact,
            Consumer<String> inspectThread,
            Consumer<WaitEdge> askAi) {
        this.inspectThread = inspectThread;
        this.askAi = askAi;
        this.compact = compact;
        addClassName(className);
    }

    public void render(AnalysisResult result) {
        this.result = result;
        highlightedLockId = null;
        renderCurrent();
    }

    public void highlightLock(String lockId) {
        if (result == null) {
            return;
        }
        highlightedLockId = lockId;
        renderCurrent();
        getElement().callJsFunction("scrollIntoView", true);
    }

    private void renderCurrent() {
        removeAll();
        HorizontalLayout header = new HorizontalLayout(new H2("Lock traffic map"));
        header.addClassName("traffic-header");
        header.setWidthFull();
        header.expand(header.getComponentAt(0));
        if (askAi != null && !result.waitEdges().isEmpty()) {
            WaitEdge primaryEdge = result.waitEdges().getFirst();
            Button ask = new Button("✦ Ask AI about this wait", event -> askAi.accept(primaryEdge));
            ask.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
            header.add(ask);
        }
        add(header, help(result));

        if (result.waitEdges().isEmpty()) {
            Div clear = new Div(new Span("✓"), new Span("All monitored lanes are flowing"));
            clear.addClassName("traffic-clear");
            add(clear);
            return;
        }

        Set<Integer> criticalThreadIds = result.deadlocks().stream()
                .flatMap(cycle -> cycle.threads().stream())
                .map(JavaThread::id)
                .collect(Collectors.toUnmodifiableSet());

        Map<Integer, JavaThread> nodes = new LinkedHashMap<>();
        result.waitEdges().forEach(edge -> {
            nodes.putIfAbsent(edge.waiter().id(), edge.waiter());
            nodes.putIfAbsent(edge.owner().id(), edge.owner());
        });
        Map<Integer, Point> points = layoutNodes(new ArrayList<>(nodes.values()));
        Set<Integer> highlightedThreadIds = result.waitEdges().stream()
                .filter(edge -> edge.lock().shortId().equals(highlightedLockId))
                .flatMap(edge -> java.util.stream.Stream.of(edge.waiter().id(), edge.owner().id()))
                .collect(Collectors.toUnmodifiableSet());

        Div canvas = new Div();
        canvas.addClassName("traffic-canvas");
        if (compact) {
            canvas.addClassName("traffic-canvas-compact");
        }
        canvas.getElement().appendChild(buildSvg(result.waitEdges(), points, criticalThreadIds));
        nodes.values().forEach(thread -> canvas.add(threadNode(
                thread, points.get(thread.id()), criticalThreadIds, highlightedThreadIds)));
        add(canvas);
    }

    private Paragraph help(AnalysisResult result) {
        String copy;
        if (result.hasDeadlock()) {
            copy = "Animated red lanes form the circular dependency. Select a node to inspect that thread.";
        } else if (!result.waitEdges().isEmpty()) {
            copy = "An ownership-dependent wait is confirmed, but no circular dependency exists yet.";
        } else {
            copy = "No ownership-dependent wait edges remain. The lock ordering is clear.";
        }
        Paragraph help = new Paragraph(copy);
        help.addClassName("panel-help");
        return help;
    }

    private Button threadNode(
            JavaThread thread,
            Point point,
            Set<Integer> criticalThreadIds,
            Set<Integer> highlightedThreadIds) {
        Span name = new Span(thread.name());
        name.addClassName("map-node-name");
        Span state = new Span(thread.state().name());
        state.addClassName("map-node-state");
        Div copy = new Div(name, state);
        copy.addClassName("map-node-copy");

        Button node = new Button();
        node.getElement().appendChild(copy.getElement());
        node.addClassNames("map-node",
                criticalThreadIds.contains(thread.id()) ? "map-node-critical" : "map-node-normal");
        if (highlightedThreadIds.contains(thread.id())) {
            node.addClassName("map-node-evidence");
        }
        node.addClickListener(event -> inspectThread.accept(thread.name()));
        node.getElement().setAttribute("aria-label", thread.name() + ", state " + thread.state());
        node.getStyle().set("left", formatPercent(point.x() / 900.0));
        node.getStyle().set("top", formatPercent(point.y() / 360.0));
        return node;
    }

    private Element buildSvg(List<WaitEdge> edges, Map<Integer, Point> points, Set<Integer> criticalThreadIds) {
        Element svg = new Element("svg");
        svg.setAttribute("viewBox", "0 0 900 360");
        svg.setAttribute("preserveAspectRatio", "xMidYMid meet");
        svg.setAttribute("role", "img");
        svg.setAttribute("aria-label", "Directional waiter-to-lock-owner map");
        svg.getClassList().add("traffic-svg");

        Element defs = new Element("defs");
        defs.appendChild(arrowMarker("tc-arrow-critical", "#ff5d73"));
        defs.appendChild(arrowMarker("tc-arrow-normal", "#72a7ff"));
        svg.appendChild(defs);

        for (WaitEdge edge : edges) {
            Point from = points.get(edge.waiter().id());
            Point to = points.get(edge.owner().id());
            double dx = to.x() - from.x();
            double dy = to.y() - from.y();
            double length = Math.max(1, Math.hypot(dx, dy));
            double ux = dx / length;
            double uy = dy / length;
            double offset = hasReverseEdge(edges, edge) ? 18 : 0;
            double px = -uy * offset;
            double py = ux * offset;
            boolean critical = criticalThreadIds.contains(edge.waiter().id());
            boolean highlighted = edge.lock().shortId().equals(highlightedLockId);

            Element line = new Element("line");
            line.setAttribute("x1", decimal(from.x() + ux * 92 + px));
            line.setAttribute("y1", decimal(from.y() + uy * 42 + py));
            line.setAttribute("x2", decimal(to.x() - ux * 92 + px));
            line.setAttribute("y2", decimal(to.y() - uy * 42 + py));
            line.setAttribute("marker-end", "url(#tc-arrow-" + (critical ? "critical" : "normal") + ")");
            line.getClassList().add(critical ? "map-edge-critical" : "map-edge-normal");
            if (highlighted) {
                line.getClassList().add("map-edge-evidence");
            }
            svg.appendChild(line);

            Element label = new Element("text");
            label.setAttribute("x", decimal((from.x() + to.x()) / 2 + px));
            label.setAttribute("y", decimal((from.y() + to.y()) / 2 + py - 8));
            label.setAttribute("text-anchor", "middle");
            label.getClassList().add("map-edge-label");
            if (highlighted) {
                label.getClassList().add("map-edge-label-evidence");
            }
            label.setText("waits for " + edge.lock().shortId());
            svg.appendChild(label);
        }
        return svg;
    }

    private Element arrowMarker(String id, String color) {
        Element marker = new Element("marker");
        marker.setAttribute("id", id);
        marker.setAttribute("viewBox", "0 0 10 10");
        marker.setAttribute("refX", "9");
        marker.setAttribute("refY", "5");
        marker.setAttribute("markerWidth", "7");
        marker.setAttribute("markerHeight", "7");
        marker.setAttribute("orient", "auto-start-reverse");
        Element path = new Element("path");
        path.setAttribute("d", "M 0 0 L 10 5 L 0 10 z");
        path.setAttribute("fill", color);
        marker.appendChild(path);
        return marker;
    }

    private Map<Integer, Point> layoutNodes(List<JavaThread> threads) {
        Map<Integer, Point> points = new LinkedHashMap<>();
        if (threads.size() == 2) {
            points.put(threads.get(0).id(), new Point(230, 180));
            points.put(threads.get(1).id(), new Point(670, 180));
            return points;
        }
        for (int index = 0; index < threads.size(); index++) {
            double angle = -Math.PI / 2 + (2 * Math.PI * index / threads.size());
            points.put(threads.get(index).id(), new Point(
                    450 + 320 * Math.cos(angle),
                    180 + 110 * Math.sin(angle)));
        }
        return points;
    }

    private boolean hasReverseEdge(List<WaitEdge> edges, WaitEdge candidate) {
        return edges.stream().anyMatch(edge -> edge.waiter().id() == candidate.owner().id()
                && edge.owner().id() == candidate.waiter().id());
    }

    private String formatPercent(double value) {
        return String.format(Locale.ROOT, "%.2f%%", value * 100);
    }

    private String decimal(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private record Point(double x, double y) {
    }
}
