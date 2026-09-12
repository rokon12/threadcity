package ca.bazlur.threadcity.domain;

import java.util.List;
import java.util.Objects;

public record ThreadSnapshot(
        String sourceName,
        List<JavaThread> threads,
        ParserDiagnostics parserDiagnostics) {

    public ThreadSnapshot {
        sourceName = Objects.requireNonNullElse(sourceName, "Thread dump");
        threads = List.copyOf(threads);
        parserDiagnostics = Objects.requireNonNullElseGet(parserDiagnostics, ParserDiagnostics::empty);
    }
}
