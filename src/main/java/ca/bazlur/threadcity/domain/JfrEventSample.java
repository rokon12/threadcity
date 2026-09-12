package ca.bazlur.threadcity.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * A bounded, presentation-safe projection of a relevant JFR event.
 */
public record JfrEventSample(
        int sequence,
        String eventType,
        String eventLabel,
        JfrEventCategory category,
        Instant startTime,
        Duration duration,
        String threadName,
        Long javaThreadId,
        String topFrame,
        String detail) {

    public JfrEventSample {
        Objects.requireNonNull(eventType, "eventType");
        eventLabel = eventLabel == null || eventLabel.isBlank() ? eventType : eventLabel;
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(startTime, "startTime");
        duration = Objects.requireNonNullElse(duration, Duration.ZERO);
        threadName = normalize(threadName);
        topFrame = normalize(topFrame);
        detail = normalize(detail);
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
