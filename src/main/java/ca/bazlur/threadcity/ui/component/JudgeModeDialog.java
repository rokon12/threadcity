package ca.bazlur.threadcity.ui.component;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.dialog.Dialog;
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
 * A guided, push-powered walkthrough that makes the evidence story easy to judge in minutes.
 */
public final class JudgeModeDialog {

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
    private final Dialog dialog = new Dialog();
    private final Span stepLabel = new Span();
    private final H2 title = new H2();
    private final Paragraph description = new Paragraph();
    private final Span proof = new Span();
    private final ProgressBar progress = new ProgressBar(0, STEPS.size(), 1);
    private final Button back = new Button("Back", VaadinIcon.ARROW_LEFT.create());
    private final Button next = new Button("Next", VaadinIcon.ARROW_RIGHT.create());
    private final Button autoplay = new Button("Auto-play", VaadinIcon.PLAY.create());
    private final AtomicLong playbackGeneration = new AtomicLong();
    private int index;

    public JudgeModeDialog(IntConsumer stepNavigator) {
        this.stepNavigator = stepNavigator;
        configure();
    }

    public void open() {
        playbackGeneration.incrementAndGet();
        autoplay.setText("Auto-play");
        autoplay.setEnabled(true);
        showStep(0);
        dialog.open();
    }

    private void configure() {
        dialog.setHeaderTitle("ThreadCity judge mode");
        dialog.setWidth("min(94vw, 660px)");
        dialog.setCloseOnEsc(true);
        dialog.setCloseOnOutsideClick(false);
        dialog.addClassName("judge-dialog");
        dialog.addOpenedChangeListener(event -> {
            if (!event.isOpened()) {
                playbackGeneration.incrementAndGet();
                autoplay.setEnabled(true);
                autoplay.setText("Auto-play");
            }
        });

        stepLabel.addClassName("judge-step-label");
        description.addClassName("judge-description");
        proof.addClassName("judge-proof");
        progress.addClassName("judge-progress");
        progress.setWidthFull();
        Div content = new Div(stepLabel, title, description, proof, progress);
        content.addClassName("judge-content");
        dialog.add(content);

        back.addClickListener(event -> showStep(index - 1));
        back.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        next.addClickListener(event -> showStep(index + 1));
        next.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        autoplay.addClickListener(event -> autoPlay());
        autoplay.addThemeVariants(ButtonVariant.LUMO_CONTRAST);
        Button close = new Button("Close", event -> dialog.close());
        close.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        HorizontalLayout controls = new HorizontalLayout(back, next, autoplay, close);
        controls.addClassName("judge-controls");
        dialog.getFooter().add(controls);
    }

    private void showStep(int requested) {
        index = Math.max(0, Math.min(requested, STEPS.size() - 1));
        Step step = STEPS.get(index);
        stepLabel.setText(step.label());
        title.setText(step.title());
        description.setText(step.description());
        proof.setText(step.proof());
        progress.setValue(index + 1);
        back.setEnabled(index > 0);
        next.setEnabled(index + 1 < STEPS.size());
        next.setText(index + 1 < STEPS.size() ? "Next" : "Tour complete");
        stepNavigator.accept(index);
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

    private record Step(String label, String title, String description, String proof) {
    }
}
