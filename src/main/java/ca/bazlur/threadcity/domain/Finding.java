package ca.bazlur.threadcity.domain;

import java.util.List;
import java.util.Objects;

public record Finding(Severity severity, String title, String explanation, List<String> threadNames) {

    public Finding {
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(explanation, "explanation");
        threadNames = List.copyOf(threadNames);
    }

    public enum Severity {
        CRITICAL,
        WARNING,
        INFO
    }
}
