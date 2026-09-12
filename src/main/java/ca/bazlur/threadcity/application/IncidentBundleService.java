package ca.bazlur.threadcity.application;

import ca.bazlur.threadcity.domain.IncidentBundle;
import ca.bazlur.threadcity.domain.NamedThreadDump;
import ca.bazlur.threadcity.parser.ThreadDumpUploadValidator;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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

    public IncidentBundle read(String fileName, byte[] bytes) {
        validateEnvelope(fileName, bytes);
        List<NamedThreadDump> dumps = new ArrayList<>();
        Set<String> names = new HashSet<>();
        byte[] recording = null;
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
                    manifest = new String(content, StandardCharsets.UTF_8);
                } else if ("recording.jfr".equals(name)) {
                    recording = content;
                } else {
                    dumps.add(new NamedThreadDump(name.substring("dumps/".length()),
                            new String(content, StandardCharsets.UTF_8)));
                }
                zip.closeEntry();
            }
        } catch (IOException exception) {
            throw new IllegalArgumentException("The incident bundle is not a readable ZIP archive", exception);
        }

        dumps.sort(Comparator.comparing(NamedThreadDump::sourceName));
        if (dumps.size() < 2 || dumps.size() > MAX_DUMPS) {
            throw new IllegalArgumentException("The bundle must contain 2–5 thread dumps in dumps/");
        }
        if (manifest.isBlank() || !manifest.contains("format=threadcity-incident-v1")) {
            throw new IllegalArgumentException("The bundle manifest is missing or unsupported");
        }
        if (recording == null || !hasJfrMagic(recording)) {
            throw new IllegalArgumentException("The bundle does not contain a valid recording.jfr header");
        }
        return new IncidentBundle(fileName, manifest, dumps, recording);
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
        if (!dump && !"manifest.properties".equals(name) && !"recording.jfr".equals(name)) {
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
        return ThreadDumpUploadValidator.MAX_BYTES;
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
}
