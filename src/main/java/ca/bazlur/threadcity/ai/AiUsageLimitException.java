package ca.bazlur.threadcity.ai;

/**
 * A fail-closed rejection produced by ThreadCity's local AI usage controls.
 */
public final class AiUsageLimitException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String userMessage;

    AiUsageLimitException(String message, String userMessage) {
        super(message);
        this.userMessage = userMessage;
    }

    public String userMessage() {
        return userMessage;
    }
}
