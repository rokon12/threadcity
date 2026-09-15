package ca.bazlur.threadcity.application;

import ca.bazlur.threadcity.domain.IncidentBundle;
import ca.bazlur.threadcity.domain.NamedThreadDump;
import ca.bazlur.threadcity.parser.ThreadDumpUploadValidator;
import ca.bazlur.threadcity.parser.Java25ThreadDumpParser;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.Map;
import java.util.Properties;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Reads the deliberately small, non-executable ThreadCity incident bundle format.
 */
@Service
public final class IncidentBundleService {

    public static final int MAX_BUNDLE_BYTES = 48 * 1024 * 1024;
    private static final int MAX_UNCOMPRESSED_BYTES = 44 * 1024 * 1024;
    private static final int MAX_ENTRIES = 8;
    private static final int MAX_DUMPS = 5;
    private static final int MAX_MANIFEST_BYTES = 64 * 1024;
    private final ThreadDumpUploadValidator dumpValidator = new ThreadDumpUploadValidator();
    private final Java25ThreadDumpParser java25Parser = new Java25ThreadDumpParser();

    public IncidentBundle read(String fileName, byte[] bytes) {
        validateEnvelope(fileName, bytes);
        Map<String, NamedThreadDump> dumpEntries = new LinkedHashMap<>();
        Set<String> names = new HashSet<>();
        byte[] recording = null;
        byte[] allThreads = null;
        String manifest = "";
        int entryCount = 0;
        int uncompressedBytes = 0;

        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entryCount++;
                if (entryCount > MAX_ENTRIES) {
                    throw new IllegalArgumentException("The bundle contains too many entries");
                }
                String name = validateEntry(entry);
                if (!names.add(name)) {
                    throw new IllegalArgumentException("The bundle contains a duplicate entry: " + name);
                }
                int limit = entryLimit(name);
                byte[] content = readBounded(zip, limit);
                uncompressedBytes += content.length;
                if (uncompressedBytes > MAX_UNCOMPRESSED_BYTES) {
                    throw new IllegalArgumentException("The expanded bundle exceeds 44 MiB");
                }
                if ("manifest.properties".equals(name)) {
                    manifest = dumpValidator.validate(
                            "manifest.txt", "text/plain; charset=UTF-8", content).content();
                } else if ("recording.jfr".equals(name)) {
                    recording = content;
                } else if ("all-threads.json".equals(name)) {
                    allThreads = content;
                } else {
                    ThreadDumpUploadValidator.ValidatedUpload validated = dumpValidator.validate(
                            name, "text/plain; charset=UTF-8", content);
                    dumpEntries.put(name, new NamedThreadDump(validated.sourceName(), validated.content()));
                }
                zip.closeEntry();
            }
        } catch (IOException exception) {
            throw new IllegalArgumentException("The incident bundle is not a readable ZIP archive", exception);
        }

        BundleManifest parsedManifest = parseManifest(manifest, dumpEntries, allThreads != null);
        List<NamedThreadDump> dumps = parsedManifest.dumpFiles().stream().map(dumpEntries::get).toList();
        if (dumps.size() < 2 || dumps.size() > MAX_DUMPS) {
            throw new IllegalArgumentException("The bundle must contain 2–5 thread dumps in dumps/");
        }
        if (recording == null || !hasJfrMagic(recording)) {
            throw new IllegalArgumentException("The bundle does not contain a valid recording.jfr header");
        }
        NamedThreadDump allThreadsDump = null;
        if (allThreads != null) {
            String content = dumpValidator.validateUtf8Text(
                    allThreads,
                    Java25ThreadDumpParser.MAX_BYTES,
                    ThreadDumpUploadValidator.MAX_LINES,
                    "The Java 25 all-thread dump");
            java25Parser.parse("all-threads.json", content.getBytes(StandardCharsets.UTF_8));
            allThreadsDump = new NamedThreadDump("all-threads.json", content);
        }
        return new IncidentBundle(fileName, manifest, dumps, allThreadsDump, recording);
    }

    private void validateEnvelope(String fileName, byte[] bytes) {
        if (fileName == null || !fileName.toLowerCase(Locale.ROOT).endsWith(".threadcity")) {
            throw new IllegalArgumentException("Choose a .threadcity incident bundle");
        }
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("The incident bundle is empty");
        }
        if (bytes.length > MAX_BUNDLE_BYTES) {
            throw new IllegalArgumentException("The incident bundle exceeds 48 MiB");
        }
        if (bytes.length < 4 || bytes[0] != 'P' || bytes[1] != 'K') {
            throw new IllegalArgumentException("The incident bundle is not a ZIP archive");
        }
    }

    private String validateEntry(ZipEntry entry) {
        if (entry.isDirectory()) {
            throw new IllegalArgumentException("Bundle directories must be implicit");
        }
        String name = entry.getName();
        if (name == null || name.isBlank() || name.startsWith("/") || name.startsWith("\\")
                || name.contains("../") || name.contains("..\\") || name.contains("\\")) {
            throw new IllegalArgumentException("The bundle contains an unsafe entry path");
        }
        boolean dump = name.startsWith("dumps/")
                && (name.toLowerCase(Locale.ROOT).endsWith(".txt")
                || name.toLowerCase(Locale.ROOT).endsWith(".log"));
        if (!dump && !"manifest.properties".equals(name) && !"recording.jfr".equals(name)
                && !"all-threads.json".equals(name)) {
            throw new IllegalArgumentException("Unsupported bundle entry: " + name);
        }
        return name;
    }

    private int entryLimit(String name) {
        if ("manifest.properties".equals(name)) {
            return MAX_MANIFEST_BYTES;
        }
        if ("recording.jfr".equals(name)) {
            return JfrAnalysisService.MAX_BYTES;
        }
        if ("all-threads.json".equals(name)) {
            return Java25ThreadDumpParser.MAX_BYTES;
        }
        return ThreadDumpUploadValidator.MAX_BYTES;
    }

    private BundleManifest parseManifest(
            String manifest,
            Map<String, NamedThreadDump> dumpEntries,
            boolean hasAllThreadsEntry) {
        if (manifest.isBlank()) {
            throw new IllegalArgumentException("The bundle manifest is missing or unsupported");
        }
        Properties properties = new Properties();
        try {
            properties.load(new StringReader(manifest));
        } catch (IOException | IllegalArgumentException exception) {
            throw new IllegalArgumentException("The bundle manifest is not readable", exception);
        }
        if (!"threadcity-incident-v1".equals(properties.getProperty("format"))) {
            throw new IllegalArgumentException("The bundle manifest is missing or unsupported");
        }

        int snapshotCount = positiveInteger(properties, "snapshot.count");
        if (snapshotCount < 2 || snapshotCount > MAX_DUMPS || snapshotCount != dumpEntries.size()) {
            throw new IllegalArgumentException("The bundle manifest snapshot count does not match its dumps");
        }
        List<String> orderedFiles = new ArrayList<>(snapshotCount);
        Instant previous = null;
        for (int index = 1; index <= snapshotCount; index++) {
            String file = requiredProperty(properties, "snapshot." + index + ".file");
            if (!dumpEntries.containsKey(file) || orderedFiles.contains(file)) {
                throw new IllegalArgumentException("The bundle manifest references an invalid or duplicate dump");
            }
            orderedFiles.add(file);
            Instant captured = instant(properties, "snapshot." + index + ".captured");
            if (previous != null && captured.isBefore(previous)) {
                throw new IllegalArgumentException("The bundle snapshots are not in chronological order");
            }
            previous = captured;
        }

        String allThreadsFile = properties.getProperty("all-threads.file");
        if (hasAllThreadsEntry != "all-threads.json".equals(allThreadsFile)) {
            throw new IllegalArgumentException("The bundle manifest does not match its Java 25 all-thread dump");
        }
        if (hasAllThreadsEntry) {
            instant(properties, "all-threads.captured");
        }
        return new BundleManifest(List.copyOf(orderedFiles));
    }

    private static int positiveInteger(Properties properties, String key) {
        String value = requiredProperty(properties, key);
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 1) {
                throw new NumberFormatException();
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("The bundle manifest has an invalid " + key, exception);
        }
    }

    private static Instant instant(Properties properties, String key) {
        try {
            return Instant.parse(requiredProperty(properties, key));
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException("The bundle manifest has an invalid " + key, exception);
        }
    }

    private static String requiredProperty(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("The bundle manifest is missing " + key);
        }
        return value.strip();
    }

    private byte[] readBounded(ZipInputStream zip, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(limit, 64 * 1024));
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = zip.read(buffer)) >= 0) {
            total += read;
            if (total > limit) {
                throw new IllegalArgumentException("A bundle entry exceeds its size limit");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private boolean hasJfrMagic(byte[] bytes) {
        return bytes.length >= 4 && bytes[0] == 'F' && bytes[1] == 'L' && bytes[2] == 'R' && bytes[3] == 0;
    }

    private record BundleManifest(List<String> dumpFiles) {
    }
}
