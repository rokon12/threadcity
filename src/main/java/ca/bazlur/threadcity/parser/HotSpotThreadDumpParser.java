package ca.bazlur.threadcity.parser;

import ca.bazlur.threadcity.domain.JavaThread;
import ca.bazlur.threadcity.domain.LockReference;
import ca.bazlur.threadcity.domain.LockWaitKind;
import ca.bazlur.threadcity.domain.ParserDiagnostics;
import ca.bazlur.threadcity.domain.ThreadSnapshot;
import ca.bazlur.threadcity.domain.ThreadState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class HotSpotThreadDumpParser {

    private static final Pattern HEADER = Pattern.compile("^\"((?:\\\\.|[^\"\\\\])*)\"(.*)$");
    private static final Pattern STATE = Pattern.compile("java\\.lang\\.Thread\\.State:\\s+([A-Z_]+)");
    private static final Pattern LOCK = Pattern.compile(
            "^-\\s+(waiting to lock|waiting on|parking to wait for|locked)\\s+<([^>]+)>\\s+\\(a\\s+([^)]+)\\).*");
    private static final Pattern OWNABLE_LOCK = Pattern.compile(
            "^-\\s+<([^>]+)>\\s+\\(a\\s+([^)]+)\\).*");
    private static final Pattern TIMESTAMP = Pattern.compile(
            "^\\d{4}-\\d{2}-\\d{2}[ T]\\d{2}:\\d{2}:\\d{2}(?:[.,]\\d+)?(?:\\s+.*)?$");
    private static final int MAX_IGNORED_SAMPLES = 50;
    private static final int MAX_IGNORED_LINE_CHARS = 240;

    public ThreadSnapshot parse(String sourceName, String dump) {
        if (dump == null || dump.isBlank()) {
            throw new IllegalArgumentException("The thread dump is empty");
        }

        dump = stripBom(dump);
        List<JavaThread> threads = new ArrayList<>();
        ThreadBuilder current = null;
        boolean ownableSynchronizers = false;
        DiagnosticsCollector diagnostics = new DiagnosticsCollector();

        for (String line : dump.split("\\R")) {
            if (line.isBlank()) {
                continue;
            }
            diagnostics.observe();
            Matcher headerMatcher = HEADER.matcher(line);
            if (headerMatcher.matches()) {
                diagnostics.recognized();
                if (current != null) {
                    threads.add(current.build(threads.size()));
                }
                current = new ThreadBuilder(unescapeThreadName(headerMatcher.group(1)), line);
                ownableSynchronizers = false;
                continue;
            }

            if (current == null) {
                if (isKnownPreamble(line.strip())) {
                    diagnostics.recognized();
                } else {
                    diagnostics.ignored(line);
                }
                continue;
            }

            String stripped = line.strip();
            if ("Locked ownable synchronizers:".equals(stripped)) {
                diagnostics.recognized();
                ownableSynchronizers = true;
                continue;
            }

            if (ownableSynchronizers) {
                Matcher ownableMatcher = OWNABLE_LOCK.matcher(stripped);
                if (ownableMatcher.matches()) {
                    diagnostics.recognized();
                    current.ownedLocks.add(new LockReference(ownableMatcher.group(1), ownableMatcher.group(2)));
                } else if ("- None".equals(stripped)) {
                    diagnostics.recognized();
                } else {
                    diagnostics.ignored(line);
                }
                continue;
            }

            Matcher stateMatcher = STATE.matcher(stripped);
            if (stateMatcher.find()) {
                diagnostics.recognized();
                current.state = ThreadState.fromDump(stateMatcher.group(1));
                continue;
            }

            if (stripped.startsWith("at ")) {
                diagnostics.recognized();
                current.stackFrames.add(stripped);
                continue;
            }

            Matcher lockMatcher = LOCK.matcher(stripped);
            if (lockMatcher.matches()) {
                diagnostics.recognized();
                LockReference lock = new LockReference(lockMatcher.group(2), lockMatcher.group(3));
                if ("locked".equals(lockMatcher.group(1))) {
                    current.ownedLocks.add(lock);
                } else if (current.waitingOn == null) {
                    current.waitingOn = lock;
                    current.waitKind = switch (lockMatcher.group(1)) {
                        case "waiting to lock" -> LockWaitKind.MONITOR_ENTRY;
                        case "waiting on" -> LockWaitKind.OBJECT_WAIT;
                        case "parking to wait for" -> LockWaitKind.PARKING;
                        default -> LockWaitKind.UNKNOWN;
                    };
                }
                continue;
            }
            diagnostics.ignored(line);
        }

        if (current != null) {
            threads.add(current.build(threads.size()));
        }
        if (threads.isEmpty()) {
            throw new IllegalArgumentException("No HotSpot thread headers were found");
        }
        return new ThreadSnapshot(sourceName, threads, diagnostics.build());
    }

    private boolean isKnownPreamble(String line) {
        return TIMESTAMP.matcher(line).matches()
                || line.startsWith("Full thread dump ")
                || line.startsWith("Threads class SMR info:")
                || line.startsWith("JNI global refs:");
    }

    private static String stripBom(String dump) {
        return dump.startsWith("\uFEFF") ? dump.substring(1) : dump;
    }

    private static String unescapeThreadName(String encoded) {
        StringBuilder name = new StringBuilder(encoded.length());
        for (int index = 0; index < encoded.length(); index++) {
            char current = encoded.charAt(index);
            if (current == '\\' && index + 1 < encoded.length()) {
                char next = encoded.charAt(index + 1);
                if (next == '\\' || next == '"') {
                    name.append(next);
                    index++;
                    continue;
                }
            }
            name.append(current);
        }
        return name.toString();
    }

    private static final class ThreadBuilder {
        private final String name;
        private final String header;
        private ThreadState state = ThreadState.UNKNOWN;
        private final List<String> stackFrames = new ArrayList<>();
        private final List<LockReference> ownedLocks = new ArrayList<>();
        private LockReference waitingOn;
        private LockWaitKind waitKind = LockWaitKind.UNKNOWN;

        private ThreadBuilder(String name, String header) {
            this.name = name;
            this.header = header;
        }

        private JavaThread build(int id) {
            return new JavaThread(id, name, header, state, stackFrames, ownedLocks, waitingOn, waitKind);
        }
    }

    private static final class DiagnosticsCollector {
        private final Map<String, Integer> ignoredSamples = new LinkedHashMap<>();
        private int contentLines;
        private int recognizedLines;
        private int ignoredLines;
        private int omittedIgnoredLines;

        private void observe() {
            contentLines++;
        }

        private void recognized() {
            recognizedLines++;
        }

        private void ignored(String line) {
            ignoredLines++;
            String safe = line.strip().replaceAll("[\\p{Cntrl}]", "?");
            if (safe.length() > MAX_IGNORED_LINE_CHARS) {
                safe = safe.substring(0, MAX_IGNORED_LINE_CHARS - 1) + "…";
            }
            if (ignoredSamples.containsKey(safe)) {
                ignoredSamples.merge(safe, 1, Integer::sum);
            } else if (ignoredSamples.size() < MAX_IGNORED_SAMPLES) {
                ignoredSamples.put(safe, 1);
            } else {
                omittedIgnoredLines++;
            }
        }

        private ParserDiagnostics build() {
            List<ParserDiagnostics.IgnoredLine> samples = ignoredSamples.entrySet().stream()
                    .map(entry -> new ParserDiagnostics.IgnoredLine(entry.getKey(), entry.getValue()))
                    .toList();
            return new ParserDiagnostics(
                    contentLines, recognizedLines, ignoredLines, samples, omittedIgnoredLines);
        }
    }
}
