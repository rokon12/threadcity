package ca.bazlur.threadcity.parser;

import ca.bazlur.threadcity.domain.JavaThread;
import ca.bazlur.threadcity.domain.LockReference;
import ca.bazlur.threadcity.domain.LockWaitKind;
import ca.bazlur.threadcity.domain.ParserDiagnostics;
import ca.bazlur.threadcity.domain.ThreadMetadata;
import ca.bazlur.threadcity.domain.ThreadSnapshot;
import ca.bazlur.threadcity.domain.ThreadState;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses the structured all-thread dump emitted by Java 25 {@code Thread.dump_to_file}.
 */
public final class Java25ThreadDumpParser {

    public static final int MAX_BYTES = 16 * 1024 * 1024;
    public static final int MAX_THREADS = 100_000;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public ThreadSnapshot parse(String sourceName, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("The Java 25 all-thread dump is empty");
        }
        if (bytes.length > MAX_BYTES) {
            throw new IllegalArgumentException("The Java 25 all-thread dump exceeds the 16 MiB limit");
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(bytes);
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("The Java 25 all-thread dump is not valid JSON", exception);
        }
        JsonNode threadDump = root.path("threadDump");
        JsonNode containers = threadDump.path("threadContainers");
        if (!threadDump.isObject() || !containers.isArray()) {
            throw new IllegalArgumentException("The JSON does not contain a Java 25 threadDump structure");
        }

        List<JavaThread> threads = new ArrayList<>();
        for (JsonNode container : containers) {
            JsonNode containerThreads = container.path("threads");
            if (!containerThreads.isArray()) {
                continue;
            }
            for (JsonNode thread : containerThreads) {
                if (threads.size() >= MAX_THREADS) {
                    throw new IllegalArgumentException("The Java 25 all-thread dump exceeds 100,000 threads");
                }
                threads.add(project(threads.size(), thread));
            }
        }
        if (threads.isEmpty()) {
            throw new IllegalArgumentException("The Java 25 all-thread dump contains no threads");
        }
        ParserDiagnostics diagnostics = new ParserDiagnostics(
                threads.size(), threads.size(), 0, List.of(), 0);
        return new ThreadSnapshot(sourceName, threads, diagnostics);
    }

    private JavaThread project(int id, JsonNode node) {
        String name = requiredText(node, "name");
        Long javaThreadId = optionalLong(node, "tid");
        boolean virtual = node.path("virtual").asBoolean(false);
        List<String> stackFrames = new ArrayList<>();
        JsonNode stack = node.path("stack");
        if (stack.isArray()) {
            stack.forEach(frame -> {
                if (frame.isString() && !frame.stringValue().isBlank()) {
                    stackFrames.add("at " + frame.stringValue().strip());
                }
            });
        }

        List<LockReference> ownedLocks = new ArrayList<>();
        JsonNode monitors = node.path("monitorsOwned");
        if (monitors.isArray()) {
            monitors.forEach(monitor -> {
                JsonNode locks = monitor.path("locks");
                if (locks.isArray()) {
                    locks.forEach(lock -> addLock(ownedLocks, lock));
                }
            });
        }
        JsonNode synchronizers = node.path("synchronizersOwned");
        if (synchronizers.isArray()) {
            synchronizers.forEach(lock -> addLock(ownedLocks, lock));
        }

        String blockedOn = optionalText(node, "blockedOn");
        String waitingOn = optionalText(node, "waitingOn");
        LockReference waitingLock = blockedOn != null
                ? lock(blockedOn)
                : waitingOn == null ? null : lock(waitingOn);
        LockWaitKind waitKind = blockedOn != null
                ? LockWaitKind.MONITOR_ENTRY
                : waitingOn == null ? LockWaitKind.UNKNOWN : LockWaitKind.OBJECT_WAIT;
        ThreadMetadata metadata = new ThreadMetadata(
                javaThreadId,
                null,
                null,
                null,
                null,
                null,
                null,
                virtual,
                virtual ? ThreadMetadata.ThreadKind.VIRTUAL : ThreadMetadata.ThreadKind.PLATFORM,
                null);
        return new JavaThread(
                id,
                name,
                "\"" + name + "\" #" + (javaThreadId == null ? "?" : javaThreadId)
                        + (virtual ? " virtual" : " platform"),
                ThreadState.fromDump(optionalText(node, "state")),
                stackFrames,
                ownedLocks,
                waitingLock,
                waitKind,
                metadata);
    }

    private static void addLock(List<LockReference> locks, JsonNode node) {
        if (node.isString() && !node.stringValue().isBlank()) {
            LockReference lock = lock(node.stringValue());
            if (!locks.contains(lock)) {
                locks.add(lock);
            }
        }
    }

    private static LockReference lock(String value) {
        String normalized = value.strip();
        int separator = normalized.lastIndexOf('@');
        String className = separator <= 0 ? "unknown" : normalized.substring(0, separator);
        return new LockReference(normalized, className);
    }

    private static String requiredText(JsonNode node, String field) {
        String value = optionalText(node, field);
        if (value == null) {
            throw new IllegalArgumentException("A Java 25 thread entry is missing " + field);
        }
        return value;
    }

    private static String optionalText(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isString() && !value.stringValue().isBlank() ? value.stringValue().strip() : null;
    }

    private static Long optionalLong(JsonNode node, String field) {
        String value = optionalText(node, field);
        if (value == null) {
            return null;
        }
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("A Java 25 thread entry has an invalid " + field, exception);
        }
    }
}
