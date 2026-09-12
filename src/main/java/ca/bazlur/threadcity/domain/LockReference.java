package ca.bazlur.threadcity.domain;

import java.util.Objects;

public record LockReference(String id, String className) {

    public LockReference {
        Objects.requireNonNull(id, "id");
        className = className == null || className.isBlank() ? "unknown" : className;
    }

    public String shortId() {
        return id.length() <= 8 ? id : id.substring(id.length() - 8);
    }
}
