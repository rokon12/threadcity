package ca.bazlur.threadcity.ai;

/**
 * Handle for an in-flight AI explanation.
 */
public interface IncidentExplanationRequest {

    void cancel();

    boolean isCancelled();
}
