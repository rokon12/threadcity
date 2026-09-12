package ca.bazlur.threadcity.collector;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Captures chronological jcmd thread dumps and one JFR recording into a ThreadCity bundle.
 */
public final class ThreadCityCollector {

    private static final int MIN_DURATION_SECONDS = 5;
    private static final int MAX_DURATION_SECONDS = 300;
    private static final int MIN_SNAPSHOTS = 2;
    private static final int MAX_SNAPSHOTS = 5;
    private static final int MAX_COMMAND_OUTPUT_BYTES = 8 * 1024 * 1024;
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(30);

    private ThreadCityCollector() {
    }

    public static void main(String[] arguments) {
        try {
            Options options = Options.parse(arguments);
            Path output = collect(options);
            System.out.println("ThreadCity bundle ready: " + output);
        } catch (IllegalArgumentException exception) {
            System.err.println("Error: " + exception.getMessage());
            System.err.println(Options.usage());
            System.exit(2);
        } catch (Exception exception) {
            System.err.println("Capture failed: " + exception.getMessage());
            System.exit(1);
        }
    }

    static Path collect(Options options) throws IOException, InterruptedException {
        Path output = options.output().toAbsolutePath().normalize();
        if (Files.exists(output)) {
            throw new IllegalArgumentException("Output already exists: " + output);
        }
        Path parent = output.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        Path jcmd = locateJcmd();
        Path workspace = Files.createTempDirectory("threadcity-capture-");
        String recordingName = "threadcity-" + options.pid() + "-" + Long.toUnsignedString(System.nanoTime());
        Path recording = workspace.resolve("recording.jfr");
        boolean recordingStarted = false;
        List<CapturedDump> dumps = new ArrayList<>();
        Instant captureStarted = Instant.now();

        try {
            command(jcmd, options.pid(), "VM.version");
            command(jcmd, options.pid(), "JFR.start",
                    "name=" + recordingName, "settings=profile", "disk=true");
            recordingStarted = true;
            System.out.println("Recording " + options.pid() + " for " + options.durationSeconds()
                    + "s with " + options.snapshots() + " thread snapshots…");

            long intervalMillis = TimeUnit.SECONDS.toMillis(options.durationSeconds()) / (options.snapshots() - 1L);
            for (int index = 0; index < options.snapshots(); index++) {
                Instant capturedAt = Instant.now();
                CommandResult dump = command(jcmd, options.pid(), "Thread.print", "-l");
                String fileName = "dumps/%02d.txt".formatted(index + 1);
                dumps.add(new CapturedDump(fileName, capturedAt, dump.output()));
                System.out.println("  snapshot " + (index + 1) + "/" + options.snapshots()
                        + " captured at " + DateTimeFormatter.ISO_INSTANT.format(capturedAt));
                if (index + 1 < options.snapshots()) {
                    Thread.sleep(intervalMillis);
                }
            }

            command(jcmd, options.pid(), "JFR.dump",
                    "name=" + recordingName, "filename=" + recording.toAbsolutePath());
            if (!Files.isRegularFile(recording) || Files.size(recording) == 0) {
                throw new IOException("jcmd did not create a readable JFR recording");
            }
            writeBundle(output, options, captureStarted, Instant.now(), dumps, recording);
            return output;
        } finally {
            if (recordingStarted) {
                try {
                    command(jcmd, options.pid(), "JFR.stop", "name=" + recordingName);
                } catch (IOException | InterruptedException exception) {
                    System.err.println("Warning: unable to stop JFR recording: " + exception.getMessage());
                    if (exception instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
            deleteWorkspace(workspace);
        }
    }

    private static void writeBundle(
            Path output,
            Options options,
            Instant started,
            Instant completed,
            List<CapturedDump> dumps,
            Path recording) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(
                output, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE))) {
            Properties manifest = new Properties();
            manifest.setProperty("format", "threadcity-incident-v1");
            manifest.setProperty("pid", options.pid());
            manifest.setProperty("capture.started", DateTimeFormatter.ISO_INSTANT.format(started));
            manifest.setProperty("capture.completed", DateTimeFormatter.ISO_INSTANT.format(completed));
            manifest.setProperty("capture.duration.seconds", Integer.toString(options.durationSeconds()));
            manifest.setProperty("snapshot.count", Integer.toString(dumps.size()));
            for (int index = 0; index < dumps.size(); index++) {
                CapturedDump dump = dumps.get(index);
                manifest.setProperty("snapshot.%d.file".formatted(index + 1), dump.fileName());
                manifest.setProperty("snapshot.%d.captured".formatted(index + 1),
                        DateTimeFormatter.ISO_INSTANT.format(dump.capturedAt()));
            }
            ByteArrayOutputStream manifestBytes = new ByteArrayOutputStream();
            manifest.store(manifestBytes, "ThreadCity incident bundle");
            add(zip, "manifest.properties", manifestBytes.toByteArray());
            for (CapturedDump dump : dumps) {
                add(zip, dump.fileName(), dump.content().getBytes(StandardCharsets.UTF_8));
            }
            zip.putNextEntry(new ZipEntry("recording.jfr"));
            Files.copy(recording, zip);
            zip.closeEntry();
        } catch (IOException exception) {
            Files.deleteIfExists(output);
            throw exception;
        }
    }

    private static void add(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(bytes);
        zip.closeEntry();
    }

    private static CommandResult command(Path jcmd, String pid, String... diagnosticCommand)
            throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(jcmd.toString());
        command.add(pid);
        command.addAll(List.of(diagnosticCommand));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        AtomicReference<IOException> readFailure = new AtomicReference<>();
        Thread reader = Thread.ofVirtual().start(() -> copyBounded(process.getInputStream(), output, readFailure));
        boolean finished = process.waitFor(COMMAND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            reader.join();
            throw new IOException("jcmd timed out while running " + diagnosticCommand[0]);
        }
        reader.join();
        if (readFailure.get() != null) {
            throw readFailure.get();
        }
        String text = output.toString(StandardCharsets.UTF_8);
        if (process.exitValue() != 0) {
            throw new IOException("jcmd " + diagnosticCommand[0] + " failed: " + summarize(text));
        }
        return new CommandResult(process.exitValue(), text);
    }

    private static void copyBounded(
            InputStream source,
            ByteArrayOutputStream target,
            AtomicReference<IOException> readFailure) {
        try (source) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = source.read(buffer)) >= 0) {
                total += read;
                if (total > MAX_COMMAND_OUTPUT_BYTES) {
                    throw new IOException("jcmd output exceeded 8 MiB");
                }
                target.write(buffer, 0, read);
            }
        } catch (IOException exception) {
            readFailure.set(exception);
        }
    }

    private static String summarize(String text) {
        String normalized = text == null ? "" : text.replaceAll("\\s+", " ").strip();
        return normalized.length() <= 400 ? normalized : normalized.substring(0, 399) + "…";
    }

    private static Path locateJcmd() {
        String binary = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                ? "jcmd.exe" : "jcmd";
        Path jcmd = Path.of(System.getProperty("java.home"), "bin", binary).toAbsolutePath().normalize();
        if (!Files.isExecutable(jcmd)) {
            throw new IllegalArgumentException("jcmd is unavailable in this JDK: " + jcmd);
        }
        return jcmd;
    }

    private static void deleteWorkspace(Path workspace) {
        try (var paths = Files.walk(workspace)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // The bundle is already closed; an OS cleanup race is non-fatal.
                }
            });
        } catch (IOException ignored) {
            // Temporary files contain only newly captured diagnostics and remain OS-managed.
        }
    }

    record Options(String pid, int durationSeconds, int snapshots, Path output) {

        static Options parse(String[] arguments) {
            Map<String, String> values = new LinkedHashMap<>();
            for (int index = 0; index < arguments.length; index++) {
                String argument = arguments[index];
                if ("--help".equals(argument) || "-h".equals(argument)) {
                    throw new IllegalArgumentException("Help requested");
                }
                if (!argument.startsWith("--") || index + 1 >= arguments.length) {
                    throw new IllegalArgumentException("Expected --option value, received: " + argument);
                }
                String value = arguments[++index];
                if (value.startsWith("--") || values.putIfAbsent(argument, value) != null) {
                    throw new IllegalArgumentException("Invalid or duplicate option: " + argument);
                }
            }
            values.keySet().stream()
                    .filter(key -> !List.of("--pid", "--duration", "--snapshots", "--output").contains(key))
                    .findFirst()
                    .ifPresent(key -> {
                        throw new IllegalArgumentException("Unknown option: " + key);
                    });
            String pid = values.get("--pid");
            if (pid == null || !pid.matches("[1-9][0-9]*")) {
                throw new IllegalArgumentException("--pid must be a positive numeric process ID");
            }
            int duration = integer(values.getOrDefault("--duration", "20"), "--duration");
            int snapshots = integer(values.getOrDefault("--snapshots", "3"), "--snapshots");
            if (duration < MIN_DURATION_SECONDS || duration > MAX_DURATION_SECONDS) {
                throw new IllegalArgumentException("--duration must be between 5 and 300 seconds");
            }
            if (snapshots < MIN_SNAPSHOTS || snapshots > MAX_SNAPSHOTS) {
                throw new IllegalArgumentException("--snapshots must be between 2 and 5");
            }
            String defaultName = "threadcity-" + pid + "-" + Instant.now().toEpochMilli() + ".threadcity";
            Path output = Path.of(values.getOrDefault("--output", defaultName));
            if (!output.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".threadcity")) {
                throw new IllegalArgumentException("--output must end with .threadcity");
            }
            return new Options(pid, duration, snapshots, output);
        }

        static String usage() {
            return "Usage: java -jar threadcity-collector.jar --pid <PID> "
                    + "[--duration 20] [--snapshots 3] [--output incident.threadcity]";
        }

        private static int integer(String value, String option) {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException(option + " must be an integer");
            }
        }
    }

    private record CapturedDump(String fileName, Instant capturedAt, String content) {
    }

    private record CommandResult(int exitCode, String output) {
    }

}
