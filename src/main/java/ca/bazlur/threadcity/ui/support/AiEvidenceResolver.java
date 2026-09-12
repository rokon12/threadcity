package ca.bazlur.threadcity.ui.support;

import ca.bazlur.threadcity.domain.AnalysisResult;
import ca.bazlur.threadcity.domain.JavaThread;
import ca.bazlur.threadcity.domain.WaitEdge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Resolves exact deterministic entities mentioned in an AI question or answer.
 */
public final class AiEvidenceResolver {

    private static final int MAX_REFERENCES = 6;

    private AiEvidenceResolver() {
    }

    public static List<AiEvidenceReference> resolve(
            AnalysisResult result,
            String question,
            String answer) {
        String text = ((question == null ? "" : question) + "\n" + (answer == null ? "" : answer))
                .toLowerCase(Locale.ROOT);
        Map<String, AiEvidenceReference> references = new LinkedHashMap<>();

        for (JavaThread thread : result.snapshot().threads()) {
            if (text.contains(thread.name().toLowerCase(Locale.ROOT))) {
                add(references, new AiEvidenceReference(
                        AiEvidenceReference.Kind.THREAD,
                        thread.name(),
                        "Thread · " + thread.name()));
            }
        }
        for (WaitEdge edge : result.waitEdges()) {
            String lockId = edge.lock().shortId();
            if (text.contains(lockId.toLowerCase(Locale.ROOT))) {
                add(references, new AiEvidenceReference(
                        AiEvidenceReference.Kind.LOCK,
                        lockId,
                        "Lock · " + lockId));
            }
        }
        result.findings().forEach(finding -> {
            if (text.contains(finding.title().toLowerCase(Locale.ROOT))) {
                add(references, new AiEvidenceReference(
                        AiEvidenceReference.Kind.FINDING,
                        finding.title(),
                        "Finding · " + finding.title()));
            }
        });

        return new ArrayList<>(references.values());
    }

    private static void add(Map<String, AiEvidenceReference> references, AiEvidenceReference reference) {
        if (references.size() < MAX_REFERENCES) {
            references.putIfAbsent(reference.kind() + ":" + reference.key(), reference);
        }
    }
}
