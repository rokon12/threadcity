package ca.bazlur.threadcity.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record JfrAnalysis(
        String sourceName,
        Instant startTime,
        Instant endTime,
        long eventsRead,
        long relevantEvents,
        boolean truncated,
        List<JfrEventSample> samples,
        List<JfrEventSummary> summaries) {

    public JfrAnalysis {
        sourceName = Objects.requireNonNullElse(sourceName, "Recording.jfr");
        samples = List.copyOf(samples);
        summaries = List.copyOf(summaries);
        if (eventsRead < 0 || relevantEvents < 0 || relevantEvents > eventsRead) {
            throw new IllegalArgumentException("Invalid JFR event counts");
        }
    }

    public Duration recordingDuration() {
        return startTime == null || endTime == null ? Duration.ZERO : Duration.between(startTime, endTime);
    }
}
