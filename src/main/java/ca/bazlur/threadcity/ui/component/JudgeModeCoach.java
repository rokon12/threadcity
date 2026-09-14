package ca.bazlur.threadcity.ui.component;

import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.progressbar.ProgressBar;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntConsumer;

/**
 * A non-modal guided walkthrough that keeps the evidence visible and interactive.
 */
public final class JudgeModeCoach extends Div {

    private static final List<Step> STEPS = List.of(
            new Step("01 · DETECT", "See the gridlock",
                    "ThreadCity parses the supplied dump and proves a circular waiter-to-owner chain.",
                    "Deterministic parser · exact lock IDs · no AI dependency"),
            new Step("02 · PRIORITIZE", "Find the root blocker",
                    "The impact graph ranks owners by direct and transitive blast radius instead of dumping a flat thread list.",
                    "TreeGrid · transitive graph traversal · evidence navigation"),
            new Step("03 · EXPLAIN", "Open the synchronizer observatory",
                    "Every observed lock becomes an inspectable object with owners, acquisition waiters, notification waiters, and risk.",
                    "Master-detail · risk sorting · lock-to-thread drill-down"),
            new Step("04 · CLUSTER", "Expose repeated work",
                    "Exact stack fingerprints and runnable method hotspots reveal cohorts hidden in raw jstack text.",
                    "Grid filters · stack fingerprints · method hotspots"),
            new Step("05 · PROVE OVER TIME", "Compare the incident timeline",
                    "Four aligned snapshots show contention appearing, becoming a deadlock, and disappearing after the fix.",
                    "Tabs · split layout · persistence detector · before/after diff"),
            new Step("06 · CORRELATE", "Add JFR time evidence",
                    "A real in-memory JFR recording links timed events to the exact dump thread names.",
                    "JDK JFR consumer · slider scrubber · dump correlation"),
            new Step("07 · HAND OFF", "Export the dossier",
                    "The operator can download a portable report containing evidence, confidence boundaries, and a verification checklist.",
                    "Vaadin DownloadHandler · escaped evidence · offline HTML"));

    private final IntConsumer stepNavigator;
    private final Span stepCounter = new Span();
    private final Span stepLabel = new Span();
    private final H2 title = new H2();
    private final Paragraph description = new Paragraph();
    private final Span proof = new Span();
    private final ProgressBar progress = new ProgressBar(0, STEPS.size(), 1);
    private final Button back = new Button("Back", VaadinIcon.ARROW_LEFT.create());
    private final Button next = new Button("Next", VaadinIcon.ARROW_RIGHT.create());
    private final Button autoplay = new Button("Auto-play", VaadinIcon.PLAY.create());
    private final Button collapse = new Button(VaadinIcon.CHEVRON_DOWN_SMALL.create());
    private final Div content = new Div();
    private final HorizontalLayout controls = new HorizontalLayout();
    private final AtomicLong playbackGeneration = new AtomicLong();
    private int index;
    private boolean collapsed;

    public JudgeModeCoach(IntConsumer stepNavigator) {
        this.stepNavigator = stepNavigator;
        configure();
    }

    public void open() {
        playbackGeneration.incrementAndGet();
        autoplay.setText("Auto-play");
        autoplay.setEnabled(true);
        setCollapsed(false);
        setVisible(true);
        showStep(0);
    }

    public void close() {
        playbackGeneration.incrementAndGet();
        autoplay.setEnabled(true);
        autoplay.setText("Auto-play");
        setVisible(false);
    }

    private void configure() {
        addClassName("judge-coach");
        setVisible(false);
        getElement().setAttribute("role", "region");
        getElement().setAttribute("aria-label", "ThreadCity judge mode");

        Span brand = new Span(VaadinIcon.STAR.create(), new Span("JUDGE MODE"));
        brand.addClassName("judge-coach-brand");
        stepCounter.addClassName("judge-coach-count");

        collapse.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE, ButtonVariant.LUMO_ICON);
        collapse.setAriaLabel("Collapse judge mode");
        collapse.addClickListener(event -> setCollapsed(!collapsed));
        Button close = new Button(VaadinIcon.CLOSE_SMALL.create(), event -> close());
        close.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE, ButtonVariant.LUMO_ICON);
        close.setAriaLabel("Close judge mode");

        HorizontalLayout headerActions = new HorizontalLayout(stepCounter, collapse, close);
        headerActions.addClassName("judge-coach-header-actions");
        headerActions.setAlignItems(HorizontalLayout.Alignment.CENTER);
        HorizontalLayout header = new HorizontalLayout(brand, headerActions);
        header.addClassName("judge-coach-header");
        header.setAlignItems(HorizontalLayout.Alignment.CENTER);
        header.expand(brand);

        stepLabel.addClassName("judge-step-label");
        description.addClassName("judge-description");
        proof.addClassName("judge-proof");
        progress.addClassName("judge-progress");
        progress.setWidthFull();
        content.add(stepLabel, title, description, proof, progress);
        content.addClassName("judge-content");
        content.getElement().setAttribute("aria-live", "polite");

        back.addClickListener(event -> showStep(index - 1));
        back.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        next.addClickListener(event -> {
            if (index + 1 < STEPS.size()) {
                showStep(index + 1);
            } else {
                close();
            }
        });
        next.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        autoplay.addClickListener(event -> autoPlay());
        autoplay.addThemeVariants(ButtonVariant.LUMO_CONTRAST);
        controls.add(back, next, autoplay);
        controls.addClassName("judge-controls");

        add(header, content, controls);
    }

    private void showStep(int requested) {
        index = Math.max(0, Math.min(requested, STEPS.size() - 1));
        Step step = STEPS.get(index);
        stepCounter.setText((index + 1) + " / " + STEPS.size());
        stepLabel.setText(step.label());
        title.setText(step.title());
        description.setText(step.description());
        proof.setText(step.proof());
        progress.setValue(index + 1);
        back.setEnabled(index > 0);
        next.setText(index + 1 < STEPS.size() ? "Next" : "Finish");
        stepNavigator.accept(index);
    }

    private void setCollapsed(boolean collapsed) {
        this.collapsed = collapsed;
        content.setVisible(!collapsed);
        controls.setVisible(!collapsed);
        collapse.setIcon((collapsed ? VaadinIcon.CHEVRON_UP_SMALL : VaadinIcon.CHEVRON_DOWN_SMALL).create());
        collapse.setAriaLabel(collapsed ? "Expand judge mode" : "Collapse judge mode");
        collapse.getElement().setAttribute("aria-expanded", String.valueOf(!collapsed));
        getElement().setAttribute("data-collapsed", String.valueOf(collapsed));
    }

    private void autoPlay() {
        long generation = playbackGeneration.incrementAndGet();
        UI ui = UI.getCurrent();
        autoplay.setEnabled(false);
        autoplay.setText("Playing…");
        Thread.ofVirtual().name("threadcity-judge-tour").start(() -> {
            for (int step = index; step < STEPS.size(); step++) {
                if (generation != playbackGeneration.get() || !ui.isAttached()) {
                    return;
                }
                int requested = step;
                ui.access(() -> showStep(requested));
                try {
                    Thread.sleep(2_200);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            if (generation == playbackGeneration.get() && ui.isAttached()) {
                ui.access(() -> {
                    autoplay.setText("Replay tour");
                    autoplay.setEnabled(true);
                });
            }
        });
    }

    @Override
    protected void onDetach(DetachEvent detachEvent) {
        playbackGeneration.incrementAndGet();
        super.onDetach(detachEvent);
    }

    private record Step(String label, String title, String description, String proof) {
    }
}
