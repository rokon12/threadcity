package ca.bazlur.threadcity.ui.component;

import ca.bazlur.threadcity.application.ThreadDumpAnalysisService;
import ca.bazlur.threadcity.domain.AnalysisResult;
import ca.bazlur.threadcity.domain.JavaThread;
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
import com.vaadin.flow.component.slider.IntegerSlider;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Self-contained, push-driven replay of the four forensic incident snapshots.
 */
public final class IncidentTimeMachine extends Div {

    private static final List<TimelineStage> STAGES = List.of(
            new TimelineStage("T−10s · Healthy baseline", "HEALTHY", "corrected.txt", "healthy",
                    "Both operations are making progress and no ownership-dependent wait is confirmed."),
            new TimelineStage("T−2s · Contention begins", "PRESSURE RISING", "contention.txt", "warning",
                    "Checkout now waits for InventoryLock. One wait edge exists, but the owner can still progress."),
            new TimelineStage("T0 · Circular wait", "GRIDLOCK", "deadlock.txt", "critical",
                    "Inventory sync requests PaymentLock and closes the cycle. Neither blocked thread can advance."),
            new TimelineStage("T+fix · Ordered locking", "RECOVERED", "corrected.txt", "healthy",
                    "Both operations acquire locks in the same order. The circular dependency disappears."));

    private final ThreadDumpAnalysisService analysisService;
    private final AtomicLong replayGeneration = new AtomicLong();
    private final LockTrafficMap trafficMap;
    private final Div moment = new Div();
    private final Span status = new Span();
    private final Paragraph narrative = new Paragraph();
    private final IntegerSlider slider = new IntegerSlider(0, STAGES.size() - 1);
    private final ProgressBar progress = new ProgressBar(0, STAGES.size() - 1, 0);
    private final Button play = new Button("Play incident", VaadinIcon.PLAY.create());

    public IncidentTimeMachine(ThreadDumpAnalysisService analysisService, Consumer<JavaThread> inspectThread) {
        this.analysisService = analysisService;
        trafficMap = new LockTrafficMap("time-machine-map", true, inspectThread);
        configureControls();
        buildLayout();
        renderStage(0);
    }

    public void selectStage(int stage) {
        slider.setValue(Math.max(0, Math.min(STAGES.size() - 1, stage)));
    }

    public void setControlsEnabled(boolean enabled) {
        slider.setEnabled(enabled);
        play.setEnabled(enabled);
    }

    public void cancelReplay() {
        replayGeneration.incrementAndGet();
        slider.setEnabled(true);
        play.setEnabled(true);
        renderStage(slider.getValue());
    }

    public void reset() {
        cancelReplay();
        slider.setValue(0);
        renderStage(0);
    }

    private void configureControls() {
        slider.setStep(1);
        slider.setValue(0);
        slider.setValueAlwaysVisible(true);
        slider.setMinMaxVisible(true);
        slider.setAriaLabel("Incident timeline snapshot");
        slider.setWidthFull();
        slider.addValueChangeListener(event -> renderStage(event.getValue()));
        progress.setWidthFull();

        play.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        play.addClickListener(event -> play());

        moment.addClassName("time-machine-moment");
        status.addClassName("time-machine-status");
        narrative.addClassName("time-machine-narrative");
        moment.add(status, narrative);
    }

    private void buildLayout() {
        Span eyebrow = new Span("FOUR FORENSIC SNAPSHOTS");
        eyebrow.addClassName("eyebrow");
        Paragraph help = new Paragraph(
                "Scrub from healthy traffic to lock contention, the circular wait, and the corrected lock order.");
        help.addClassName("panel-help");
        HorizontalLayout controls = new HorizontalLayout(play);
        controls.addClassName("time-machine-actions");

        add(eyebrow, new H2("Incident Time Machine"), help, progress, slider, moment, trafficMap, controls);
        addClassNames("panel", "time-machine");
    }

    private void renderStage(int index) {
        int safeIndex = Math.max(0, Math.min(STAGES.size() - 1, index));
        TimelineStage stage = STAGES.get(safeIndex);
        progress.setValue(safeIndex);
        status.setText(stage.moment() + " · " + stage.status());
        narrative.setText(stage.narrative());
        moment.removeClassNames("moment-healthy", "moment-warning", "moment-critical");
        moment.addClassName("moment-" + stage.tone());
        trafficMap.removeClassNames("timeline-stage-healthy", "timeline-stage-warning", "timeline-stage-critical");
        trafficMap.addClassName("timeline-stage-" + stage.tone());
        AnalysisResult result = analysisService.analyzeSample(stage.moment(), stage.fixture());
        trafficMap.render(result);
        play.setText(safeIndex == STAGES.size() - 1 ? "Replay incident" : "Play incident");
        play.setIcon(VaadinIcon.PLAY.create());
    }

    private void play() {
        long generation = replayGeneration.incrementAndGet();
        int current = slider.getValue();
        int start = current >= STAGES.size() - 1 ? 0 : current;
        slider.setValue(start);
        slider.setEnabled(false);
        play.setEnabled(false);
        play.setText("Replaying…");
        UI ui = UI.getCurrent();

        Thread.ofVirtual().name("threadcity-time-machine").start(() -> {
            for (int index = start + 1; index < STAGES.size(); index++) {
                try {
                    Thread.sleep(Duration.ofMillis(850));
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (generation != replayGeneration.get() || !ui.isAttached()) {
                    return;
                }
                int snapshot = index;
                ui.access(() -> slider.setValue(snapshot));
            }
            if (generation == replayGeneration.get() && ui.isAttached()) {
                ui.access(() -> {
                    slider.setEnabled(true);
                    play.setEnabled(true);
                    play.setText("Replay incident");
                    play.setIcon(VaadinIcon.REFRESH.create());
                });
            }
        });
    }

    private record TimelineStage(String moment, String status, String fixture, String tone, String narrative) {
    }
}
