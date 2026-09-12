package ca.bazlur.threadcity.ai;

import java.util.Objects;

/**
 * One completed operator/assistant exchange for the currently analyzed snapshot.
 */
public record IncidentConversationTurn(String question, String answer) {

    public IncidentConversationTurn {
        question = requireText(question, "question");
        answer = requireText(answer, "answer");
    }

    private static String requireText(String value, String name) {
        String text = Objects.requireNonNull(value, name).strip();
        if (text.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return text;
    }
}
