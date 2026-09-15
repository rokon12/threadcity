package ca.bazlur.threadcity.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Builds conservative identities for correlating threads across nearby snapshots and tools.
 */
public final class ThreadIdentities {

    private ThreadIdentities() {
    }

    public static Map<String, JavaThread> index(List<JavaThread> threads) {
        Map<String, Integer> totals = new LinkedHashMap<>();
        threads.forEach(thread -> totals.merge(baseKey(thread), 1, Integer::sum));
        Map<String, Integer> occurrences = new LinkedHashMap<>();
        Map<String, JavaThread> indexed = new LinkedHashMap<>();
        threads.forEach(thread -> {
            String base = baseKey(thread);
            int occurrence = occurrences.merge(base, 1, Integer::sum);
            String key = totals.get(base) == 1 ? base : base + "/occurrence:" + occurrence;
            indexed.put(key, thread);
        });
        return Collections.unmodifiableMap(indexed);
    }

    public static Map<Integer, String> keysByInternalId(List<JavaThread> threads) {
        Map<Integer, String> keys = new LinkedHashMap<>();
        index(threads).forEach((key, thread) -> keys.put(thread.id(), key));
        return Collections.unmodifiableMap(keys);
    }

    public static Optional<JavaThread> match(JavaThread reference, List<JavaThread> candidates) {
        Optional<JavaThread> sameValue = candidates.stream().filter(reference::equals).findFirst();
        if (sameValue.isPresent()) {
            return sameValue;
        }
        List<JavaThread> stableMatches = candidates.stream()
                .filter(candidate -> baseKey(candidate).equals(baseKey(reference)))
                .toList();
        if (stableMatches.size() == 1) {
            return Optional.of(stableMatches.getFirst());
        }
        return uniquelyNamed(reference.name(), candidates);
    }

    public static Optional<JavaThread> matchJfr(
            Long javaThreadId,
            String threadName,
            List<JavaThread> candidates) {
        if (javaThreadId != null) {
            List<JavaThread> idMatches = candidates.stream()
                    .filter(thread -> javaThreadId.equals(thread.metadata().javaThreadNumber()))
                    .toList();
            if (idMatches.size() == 1) {
                return Optional.of(idMatches.getFirst());
            }
        }
        return uniquelyNamed(threadName, candidates);
    }

    public static Optional<JavaThread> uniquelyNamed(String name, List<JavaThread> candidates) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        List<JavaThread> matches = candidates.stream().filter(thread -> thread.name().equals(name)).toList();
        return matches.size() == 1 ? Optional.of(matches.getFirst()) : Optional.empty();
    }

    public static String displayKey(String key, JavaThread thread) {
        ThreadMetadata metadata = thread.metadata();
        if (metadata.javaThreadNumber() != null) {
            return thread.name() + " · Java #" + metadata.javaThreadNumber();
        }
        if (metadata.tid() != null) {
            return thread.name() + " · tid=" + metadata.tid();
        }
        if (metadata.nid() != null) {
            return thread.name() + " · nid=" + metadata.nid();
        }
        int occurrence = key.lastIndexOf("/occurrence:");
        return occurrence < 0 ? thread.name() : thread.name() + " [" + key.substring(occurrence + 12) + "]";
    }

    private static String baseKey(JavaThread thread) {
        ThreadMetadata metadata = thread.metadata();
        if (metadata.tid() != null) {
            return "tid:" + metadata.tid().toLowerCase(Locale.ROOT);
        }
        if (metadata.javaThreadNumber() != null) {
            return "java:" + metadata.javaThreadNumber();
        }
        if (metadata.nid() != null) {
            return "nid:" + metadata.nid().toLowerCase(Locale.ROOT);
        }
        return "name:" + thread.name();
    }
}
