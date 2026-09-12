package ca.bazlur.threadcity.domain;

import java.util.Objects;

public record NamedThreadDump(String sourceName, String content) {

    public NamedThreadDump {
        Objects.requireNonNull(sourceName, "sourceName");
        Objects.requireNonNull(content, "content");
    }
}
