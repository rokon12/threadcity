package ca.bazlur.threadcity.application;

import ca.bazlur.threadcity.domain.JfrAnalysis;
import ca.bazlur.threadcity.domain.JfrEventCategory;
import ca.bazlur.threadcity.domain.JfrEventSample;
import ca.bazlur.threadcity.domain.JfrEventSummary;
import jdk.jfr.consumer.RecordedClass;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordedThread;
import jdk.jfr.consumer.RecordingFile;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.Recording;
import jdk.jfr.StackTrace;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Streams a bounded set of trusted JFR evidence into immutable domain projections.
 */
@Service
public final class JfrAnalysisService {

    public static final int MAX_BYTES = 32 * 1024 * 1024;
    public static final int MAX_EVENTS = 250_000;
    public static final int MAX_SAMPLES = 4_000;

    public JfrAnalysis analyze(String sourceName, byte[] bytes) {
        validate(sourceName, bytes);
        Path recording = null;
        try {
            recording = Files.createTempFile("threadcity-recording-", ".jfr");
            Files.write(recording, bytes);
            return read(sourceName, recording);
        } catch (IOException | RuntimeException exception) {
            throw new IllegalArgumentException("The uploaded file is not a readable JFR recording", exception);
        } finally {
            if (recording != null) {
                try {
                    Files.deleteIfExists(recording);
                } catch (IOException ignored) {
                    // The operating system will eventually reclaim its temporary directory.
                }
            }
        }
    }

    public JfrAnalysis analyzeDemo() {
        Path recordingFile = null;
        try (Recording recording = new Recording()) {
            recording.enable(DemoEvidenceEvent.class).withStackTrace();
            recording.start();
            Thread checkout = Thread.ofPlatform().name("checkout-37")
                    .start(() -> emitDemoEvidence("checkout waits for inventory", 4));
            Thread inventory = Thread.ofPlatform().name("inventory-sync-12")
                    .start(() -> emitDemoEvidence("inventory waits for payment", 3));
            checkout.join();
            inventory.join();
            recording.stop();
            recordingFile = Files.createTempFile("threadcity-demo-", ".jfr");
            recording.dump(recordingFile);
            return analyze("threadcity-demo.jfr", Files.readAllBytes(recordingFile));
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to create the built-in JFR demonstration", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Built-in JFR demonstration was interrupted", exception);
        } finally {
            if (recordingFile != null) {
                try {
                    Files.deleteIfExists(recordingFile);
                } catch (IOException ignored) {
                    // The operating system will reclaim its temporary directory.
                }
            }
        }
    }

    private void emitDemoEvidence(String scenario, int pressure) {
        for (int index = 1; index <= pressure; index++) {
            DemoEvidenceEvent event = new DemoEvidenceEvent();
            event.scenario = scenario;
            event.pressure = index;
            event.begin();
            try {
                Thread.sleep(8);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            event.commit();
        }
    }

    private JfrAnalysis read(String sourceName, Path path) throws IOException {
        long eventsRead = 0;
        long relevantEvents = 0;
        boolean truncated = false;
        Instant start = null;
        Instant end = null;
        Map<String, SummaryAccumulator> summaries = new LinkedHashMap<>();
        java.util.ArrayList<JfrEventSample> samples = new java.util.ArrayList<>();

        try (RecordingFile recording = new RecordingFile(path)) {
            while (recording.hasMoreEvents()) {
                if (eventsRead >= MAX_EVENTS) {
                    truncated = true;
                    break;
                }
                RecordedEvent event = recording.readEvent();
                eventsRead++;
                JfrEventCategory category = category(event.getEventType().getName());
                if (category == null) {
                    continue;
                }
                relevantEvents++;
                Instant eventStart = event.getStartTime();
                Instant eventEnd = event.getEndTime();
                start = start == null || eventStart.isBefore(start) ? eventStart : start;
                end = end == null || eventEnd.isAfter(end) ? eventEnd : end;
                Duration duration = safeDuration(event.getDuration());
                String type = event.getEventType().getName();
                String label = event.getEventType().getLabel();
                summaries.computeIfAbsent(type, ignored -> new SummaryAccumulator(type, label, category))
                        .add(duration);
                if (samples.size() < MAX_SAMPLES) {
                    samples.add(project(samples.size() + 1, event, category, duration));
                } else {
                    truncated = true;
                }
            }
        }

        List<JfrEventSummary> resultSummaries = summaries.values().stream()
                .map(SummaryAccumulator::build)
                .sorted(Comparator.comparingLong(JfrEventSummary::count).reversed()
                        .thenComparing(JfrEventSummary::eventType))
                .toList();
        return new JfrAnalysis(
                safeSourceName(sourceName),
                start,
                end,
                eventsRead,
                relevantEvents,
                truncated,
                samples,
                resultSummaries);
    }

    private JfrEventSample project(
            int sequence,
            RecordedEvent event,
            JfrEventCategory category,
            Duration duration) {
        RecordedThread thread = firstThread(event, "eventThread", "sampledThread", "thread");
        return new JfrEventSample(
                sequence,
                event.getEventType().getName(),
                event.getEventType().getLabel(),
                category,
                event.getStartTime(),
                duration,
                thread == null ? null : thread.getJavaName(),
                thread == null || thread.getJavaThreadId() < 0 ? null : thread.getJavaThreadId(),
                topFrame(event.getStackTrace()),
                detail(event, category));
    }

    private JfrEventCategory category(String eventName) {
        if (eventName.startsWith("threadcity.lab.")) {
            return JfrEventCategory.LAB;
        }
        return switch (eventName) {
            case "jdk.JavaMonitorEnter", "jdk.JavaMonitorWait", "jdk.JavaMonitorInflate" ->
                    JfrEventCategory.MONITOR;
            case "jdk.ThreadPark" -> JfrEventCategory.PARK;
            case "jdk.ExecutionSample", "jdk.NativeMethodSample", "jdk.CPULoad" -> JfrEventCategory.CPU;
            case "jdk.SocketRead", "jdk.SocketWrite", "jdk.FileRead", "jdk.FileWrite" -> JfrEventCategory.IO;
            case "jdk.GarbageCollection", "jdk.GCPhasePause", "jdk.GCPhasePauseLevel1",
                    "jdk.GCPhasePauseLevel2", "jdk.GCPhasePauseLevel3", "jdk.GCPhasePauseLevel4" ->
                    JfrEventCategory.GC;
            case "jdk.VirtualThreadPinned", "jdk.VirtualThreadSubmitFailed",
                    "jdk.VirtualThreadStart", "jdk.VirtualThreadEnd" -> JfrEventCategory.VIRTUAL_THREAD;
            default -> null;
        };
    }

    private String detail(RecordedEvent event, JfrEventCategory category) {
        return switch (category) {
            case MONITOR -> firstField(event, "monitorClass", "previousOwner", "address");
            case PARK -> firstField(event, "parkedClass", "timeout", "until");
            case IO -> firstField(event, "host", "path", "bytesRead", "bytesWritten");
            case GC -> firstField(event, "name", "cause", "gcId");
            case VIRTUAL_THREAD -> firstField(event, "eventThread", "exceptionMessage");
            case CPU -> firstField(event, "jvmUser", "machineTotal", "state");
            case LAB -> firstField(event, "scenario", "pressure", "message");
        };
    }

    private String firstField(RecordedEvent event, String... names) {
        for (String name : names) {
            if (!event.hasField(name)) {
                continue;
            }
            Object value;
            try {
                value = event.getValue(name);
            } catch (RuntimeException ignored) {
                continue;
            }
            if (value == null) {
                continue;
            }
            String text = value instanceof RecordedClass recordedClass
                    ? recordedClass.getName()
                    : value.toString();
            text = text.lines().findFirst().orElse("").strip();
            if (!text.isBlank()) {
                return text.length() <= 240 ? text : text.substring(0, 239) + "…";
            }
        }
        return null;
    }

    private RecordedThread firstThread(RecordedEvent event, String... fields) {
        for (String field : fields) {
            if (event.hasField(field)) {
                try {
                    RecordedThread thread = event.getThread(field);
                    if (thread != null) {
                        return thread;
                    }
                } catch (RuntimeException ignored) {
                    // Event schemas vary between JDK releases and recording settings.
                }
            }
        }
        return null;
    }

    private String topFrame(RecordedStackTrace stackTrace) {
        if (stackTrace == null || stackTrace.getFrames().isEmpty()) {
            return null;
        }
        RecordedFrame frame = stackTrace.getFrames().getFirst();
        String method = frame.getMethod().getType().getName() + "." + frame.getMethod().getName();
        return frame.getLineNumber() > 0 ? method + ":" + frame.getLineNumber() : method;
    }

    private Duration safeDuration(Duration duration) {
        return duration == null || duration.isNegative() ? Duration.ZERO : duration;
    }

    private void validate(String sourceName, byte[] bytes) {
        String normalized = sourceName == null ? "" : sourceName.toLowerCase(Locale.ROOT);
        if (!normalized.endsWith(".jfr")) {
            throw new IllegalArgumentException("Choose a .jfr recording");
        }
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("The JFR recording is empty");
        }
        if (bytes.length > MAX_BYTES) {
            throw new IllegalArgumentException("The JFR recording exceeds the 32 MiB limit");
        }
        if (bytes.length < 4 || bytes[0] != 'F' || bytes[1] != 'L' || bytes[2] != 'R' || bytes[3] != 0) {
            throw new IllegalArgumentException("The uploaded file does not have a JFR header");
        }
    }

    private String safeSourceName(String sourceName) {
        if (sourceName == null || sourceName.isBlank()) {
            return "Recording.jfr";
        }
        String normalized = sourceName.replace('\\', '/');
        normalized = normalized.substring(normalized.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "?").strip();
        return normalized.length() <= 120 ? normalized : normalized.substring(0, 117) + "...";
    }

    private static final class SummaryAccumulator {
        private final String eventType;
        private final String eventLabel;
        private final JfrEventCategory category;
        private long count;
        private Duration total = Duration.ZERO;
        private Duration maximum = Duration.ZERO;

        private SummaryAccumulator(String eventType, String eventLabel, JfrEventCategory category) {
            this.eventType = eventType;
            this.eventLabel = eventLabel;
            this.category = category;
        }

        private void add(Duration duration) {
            count++;
            total = total.plus(duration);
            if (duration.compareTo(maximum) > 0) {
                maximum = duration;
            }
        }

        private JfrEventSummary build() {
            return new JfrEventSummary(eventType, eventLabel, category, count, total, maximum);
        }
    }

    @Name("threadcity.lab.DemoEvidence")
    @Label("ThreadCity demo pressure")
    @StackTrace(true)
    private static final class DemoEvidenceEvent extends Event {
        @Label("Scenario")
        private String scenario;

        @Label("Pressure")
        private int pressure;
    }
}
