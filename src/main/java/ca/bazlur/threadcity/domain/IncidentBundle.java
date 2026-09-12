package ca.bazlur.threadcity.domain;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record IncidentBundle(
        String sourceName,
        String manifest,
        List<NamedThreadDump> threadDumps,
        byte[] jfrRecording) {

    public IncidentBundle {
        Objects.requireNonNull(sourceName, "sourceName");
        manifest = manifest == null ? "" : manifest;
        threadDumps = List.copyOf(threadDumps);
        jfrRecording = jfrRecording == null ? null : jfrRecording.clone();
        if (threadDumps.size() < 2 || threadDumps.size() > 5) {
            throw new IllegalArgumentException("An incident bundle needs 2–5 chronological thread dumps");
        }
    }

    @Override
    public byte[] jfrRecording() {
        return jfrRecording == null ? null : jfrRecording.clone();
    }

    public Optional<byte[]> recording() {
        return Optional.ofNullable(jfrRecording());
    }
}
