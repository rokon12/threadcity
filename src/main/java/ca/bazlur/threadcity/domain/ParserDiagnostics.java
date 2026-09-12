package ca.bazlur.threadcity.domain;

import java.util.List;
import java.util.Objects;

/**
 * Bounded accounting of meaningful input lines understood by the parser.
 */
public record ParserDiagnostics(
        int contentLines,
        int recognizedLines,
        int ignoredLines,
        List<IgnoredLine> ignoredLineSamples,
        int omittedIgnoredLines) {

    public ParserDiagnostics {
        ignoredLineSamples = List.copyOf(ignoredLineSamples);
        if (contentLines < 0 || recognizedLines < 0 || ignoredLines < 0 || omittedIgnoredLines < 0
                || recognizedLines + ignoredLines != contentLines) {
            throw new IllegalArgumentException("Invalid parser diagnostic counts");
        }
    }

    public double coverageRatio() {
        return contentLines == 0 ? 1.0 : (double) recognizedLines / contentLines;
    }

    public int coveragePercent() {
        return (int) Math.round(coverageRatio() * 100);
    }

    public Confidence confidence() {
        if (coverageRatio() >= 0.90) {
            return Confidence.HIGH;
        }
        if (coverageRatio() >= 0.70) {
            return Confidence.PARTIAL;
        }
        return Confidence.LOW;
    }

    public static ParserDiagnostics empty() {
        return new ParserDiagnostics(0, 0, 0, List.of(), 0);
    }

    public record IgnoredLine(String text, int occurrences) {
        public IgnoredLine {
            Objects.requireNonNull(text, "text");
            if (occurrences < 1) {
                throw new IllegalArgumentException("Ignored line occurrences must be positive");
            }
        }
    }

    public enum Confidence {
        HIGH("High confidence"),
        PARTIAL("Review suggested"),
        LOW("Low confidence");

        private final String label;

        Confidence(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }
}
