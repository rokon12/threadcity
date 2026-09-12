package ca.bazlur.threadcity.ui.support;

import ca.bazlur.threadcity.domain.AnalysisResult;
import ca.bazlur.threadcity.domain.JavaThread;

import java.util.stream.Collectors;

public final class IncidentNarrative {

    private IncidentNarrative() {
    }

    public static String diagnosis(AnalysisResult result) {
        if (!result.hasDeadlock()) {
            return "No circular waiter-to-owner chain was confirmed in this snapshot.";
        }
        return result.deadlocks().getFirst().edges().stream()
                .map(edge -> edge.waiter().name() + " waits for " + edge.lock().shortId()
                        + " owned by " + edge.owner().name())
                .collect(Collectors.joining("; ")) + ".";
    }

    public static String threadDetails(JavaThread thread) {
        StringBuilder text = new StringBuilder();
        text.append('"').append(thread.name()).append('"').append(System.lineSeparator());
        text.append("State: ").append(thread.state()).append(System.lineSeparator());
        if (thread.waitingOn() != null) {
            text.append("Wait: ").append(thread.waitKind().description()).append(System.lineSeparator());
            text.append("Target: ").append(thread.waitingOn().id())
                    .append(" (").append(thread.waitingOn().className()).append(")")
                    .append(System.lineSeparator());
        }
        if (!thread.ownedLocks().isEmpty()) {
            text.append("Owns: ").append(thread.ownedLocks().stream()
                    .map(lock -> lock.id() + " (" + lock.className() + ")")
                    .collect(Collectors.joining(", ")))
                    .append(System.lineSeparator());
        }
        text.append(System.lineSeparator());
        thread.stackFrames().forEach(frame -> text.append("  ").append(frame).append(System.lineSeparator()));
        return text.toString();
    }
}
