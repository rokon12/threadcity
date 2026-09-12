package ca.bazlur.threadcity.ui.support;

import java.util.Objects;

/**
 * A deterministic ThreadCity entity mentioned in an AI exchange.
 */
public record AiEvidenceReference(Kind kind, String key, String label) {

    public AiEvidenceReference {
        Objects.requireNonNull(kind, "kind");
        key = Objects.requireNonNull(key, "key").strip();
        label = Objects.requireNonNull(label, "label").strip();
    }

    public enum Kind {
        THREAD,
        LOCK,
        FINDING
    }
}
