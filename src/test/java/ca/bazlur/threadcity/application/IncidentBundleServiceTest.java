package ca.bazlur.threadcity.application;

import ca.bazlur.threadcity.domain.IncidentBundle;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IncidentBundleServiceTest {

    private final IncidentBundleService service = new IncidentBundleService();

    @Test
    void readsChronologicalDumpsManifestAndRecording() throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("manifest.properties", manifest("""
                snapshot.1.file=dumps/02.txt
                snapshot.1.captured=2026-09-15T12:00:00Z
                snapshot.2.file=dumps/01.txt
                snapshot.2.captured=2026-09-15T12:00:05Z
                all-threads.file=all-threads.json
                all-threads.captured=2026-09-15T12:00:06Z
                """));
        entries.put("dumps/02.txt", "\"second\" #2\n".getBytes(StandardCharsets.UTF_8));
        entries.put("dumps/01.txt", "\"first\" #1\n".getBytes(StandardCharsets.UTF_8));
        entries.put("all-threads.json", """
                {"threadDump":{"threadContainers":[{"threads":[
                  {"tid":"81","name":"virtual-request","virtual":true,"state":"WAITING","stack":[]}
                ]}]}}
                """.getBytes(StandardCharsets.UTF_8));
        entries.put("recording.jfr", new byte[]{'F', 'L', 'R', 0, 1, 2, 3});

        IncidentBundle bundle = service.read("checkout.threadcity", zip(entries));

        assertThat(bundle.threadDumps()).extracting(dump -> dump.sourceName())
                .containsExactly("02.txt", "01.txt");
        assertThat(bundle.allThreads()).isPresent();
        assertThat(bundle.recording()).hasValueSatisfying(recording ->
                assertThat(recording).startsWith('F', 'L', 'R', 0));
        byte[] leaked = bundle.jfrRecording();
        leaked[0] = 0;
        assertThat(bundle.jfrRecording()[0]).isEqualTo((byte) 'F');
    }

    @Test
    void rejectsTraversalEntriesEvenThoughImporterNeverExtractsThem() throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("manifest.properties", "format=threadcity-incident-v1\n".getBytes(StandardCharsets.UTF_8));
        entries.put("../stolen.txt", "bad".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> service.read("unsafe.threadcity", zip(entries)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsafe entry path");
    }

    @Test
    void rejectsInvalidUtf8InsideDumpEntries() throws IOException {
        Map<String, byte[]> entries = validEntries();
        entries.put("dumps/01.txt", new byte[]{(byte) 0xc3, (byte) 0x28});

        assertThatThrownBy(() -> service.read("invalid.threadcity", zip(entries)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("valid UTF-8");
    }

    @Test
    void rejectsManifestCountsThatDoNotMatchTheArchive() throws IOException {
        Map<String, byte[]> entries = validEntries();
        entries.put("manifest.properties", ("format=threadcity-incident-v1\n"
                + "snapshot.count=3\n").getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> service.read("mismatch.threadcity", zip(entries)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("snapshot count");
    }

    @Test
    void rejectsUnknownEntriesAndInvalidEnvelope() throws IOException {
        assertThatThrownBy(() -> service.read("wrong.zip", new byte[]{'P', 'K', 3, 4}))
                .hasMessageContaining(".threadcity");

        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("manifest.properties", "format=threadcity-incident-v1\n".getBytes(StandardCharsets.UTF_8));
        entries.put("payload.class", new byte[]{1});
        assertThatThrownBy(() -> service.read("unknown.threadcity", zip(entries)))
                .hasMessageContaining("Unsupported bundle entry");
    }

    private byte[] zip(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static Map<String, byte[]> validEntries() {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("manifest.properties", manifest("""
                snapshot.1.file=dumps/01.txt
                snapshot.1.captured=2026-09-15T12:00:00Z
                snapshot.2.file=dumps/02.txt
                snapshot.2.captured=2026-09-15T12:00:05Z
                """));
        entries.put("dumps/01.txt", "\"first\" #1\n".getBytes(StandardCharsets.UTF_8));
        entries.put("dumps/02.txt", "\"second\" #2\n".getBytes(StandardCharsets.UTF_8));
        entries.put("recording.jfr", new byte[]{'F', 'L', 'R', 0});
        return entries;
    }

    private static byte[] manifest(String snapshots) {
        return ("format=threadcity-incident-v1\n"
                + "snapshot.count=2\n"
                + snapshots).getBytes(StandardCharsets.UTF_8);
    }
}
