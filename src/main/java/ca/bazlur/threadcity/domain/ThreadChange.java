package ca.bazlur.threadcity.domain;

import java.util.Objects;
import java.util.Set;

/**
 * Differences for one occurrence of a thread name across two snapshots.
 */
public record ThreadChange(
        String identity,
        JavaThread before,
        JavaThread after,
        Set<Kind> kinds) {

    public ThreadChange {
        Objects.requireNonNull(identity, "identity");
        kinds = Set.copyOf(kinds);
        if (before == null && after == null) {
            throw new IllegalArgumentException("A thread change needs at least one side");
        }
        if (kinds.isEmpty()) {
            throw new IllegalArgumentException("A thread change needs a classification");
        }
    }

    public String threadName() {
        return after == null ? before.name() : after.name();
    }

    public String beforeState() {
        return before == null ? "—" : before.state().name();
    }

    public String afterState() {
        return after == null ? "—" : after.state().name();
    }

    public String beforeTopFrame() {
        return before == null ? "—" : before.topFrame();
    }

    public String afterTopFrame() {
        return after == null ? "—" : after.topFrame();
    }

    public boolean changed() {
        return !kinds.contains(Kind.UNCHANGED);
    }

    public enum Kind {
        ADDED("New thread"),
        REMOVED("Disappeared"),
        STATE_CHANGED("State changed"),
        WAIT_CHANGED("Wait changed"),
        STACK_CHANGED("Stack moved"),
        UNCHANGED("Unchanged");

        private final String label;

        Kind(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }
}
