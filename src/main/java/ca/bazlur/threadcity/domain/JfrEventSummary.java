package ca.bazlur.threadcity.domain;

import java.time.Duration;
import java.util.Objects;

public record JfrEventSummary(
        String eventType,
        String eventLabel,
        JfrEventCategory category,
        long count,
        Duration totalDuration,
        Duration maximumDuration) {

    public JfrEventSummary {
        Objects.requireNonNull(eventType, "eventType");
        eventLabel = eventLabel == null || eventLabel.isBlank() ? eventType : eventLabel;
        Objects.requireNonNull(category, "category");
        totalDuration = Objects.requireNonNullElse(totalDuration, Duration.ZERO);
        maximumDuration = Objects.requireNonNullElse(maximumDuration, Duration.ZERO);
        if (count < 1) {
            throw new IllegalArgumentException("A JFR event summary needs at least one event");
        }
    }
}
