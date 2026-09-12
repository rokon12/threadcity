package ca.bazlur.threadcity.ui.component;

import ca.bazlur.threadcity.domain.AnalysisResult;
import ca.bazlur.threadcity.domain.IncidentPattern;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.details.Details;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;

import java.util.Locale;
import java.util.function.Consumer;

/**
 * Presents explainable incident signatures with their confidence boundary and raw evidence.
 */
public final class IncidentPatternPanel extends Div {

    private final Consumer<String> threadInspector;
    private final Div content = new Div();

    public IncidentPatternPanel(Consumer<String> threadInspector) {
        this.threadInspector = threadInspector;
        Span eyebrow = new Span("DETERMINISTIC SIGNATURE ENGINE");
        eyebrow.addClassName("eyebrow");
        Paragraph help = new Paragraph(
                "Recognizes JVM failure shapes and states exactly how strong the evidence is. Suspects remain suspects until corroborated.");
        help.addClassName("panel-help");
        content.addClassName("pattern-grid");
        add(eyebrow, new H2("Incident pattern radar"), help, content);
        addClassNames("panel", "incident-pattern-panel");
    }

    public void render(AnalysisResult result) {
        content.removeAll();
        if (result.patterns().isEmpty()) {
            Div empty = new Div(
                    VaadinIcon.CHECK_CIRCLE.create(),
                    new H2("No high-signal failure pattern found"),
                    new Paragraph("This snapshot can still contain application-specific problems. Compare more dumps or add JFR evidence."));
            empty.addClassName("pattern-empty");
            content.add(empty);
            return;
        }
        result.patterns().forEach(pattern -> content.add(patternCard(pattern)));
    }

    public void clear() {
        content.removeAll();
    }

    private Div patternCard(IncidentPattern pattern) {
        Span type = new Span(pattern.type().label());
        type.addClassName("pattern-type");
        Span confidence = new Span(pattern.confidence().label());
        confidence.addClassNames(
                "pattern-confidence",
                "pattern-confidence-" + pattern.confidence().name().toLowerCase(Locale.ROOT));
        HorizontalLayout badges = new HorizontalLayout(type, confidence);
        badges.setPadding(false);
        badges.setSpacing(true);
        badges.addClassName("pattern-badges");

        Div evidence = new Div();
        evidence.addClassName("pattern-evidence");
        pattern.evidence().forEach(line -> evidence.add(new Span(line)));
        Details evidenceDetails = new Details("Why ThreadCity flagged this", evidence);
        evidenceDetails.addClassName("pattern-details");

        Div card = new Div(badges, new H2(pattern.title()), new Paragraph(pattern.explanation()), evidenceDetails);
        card.addClassNames(
                "pattern-card",
                "pattern-" + pattern.severity().name().toLowerCase(Locale.ROOT));
        if (!pattern.threadNames().isEmpty()) {
            Button inspect = new Button(
                    "Inspect evidence threads",
                    VaadinIcon.SEARCH.create(),
                    event -> threadInspector.accept(pattern.threadNames().getFirst()));
            inspect.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE);
            card.add(inspect);
        }
        return card;
    }
}
