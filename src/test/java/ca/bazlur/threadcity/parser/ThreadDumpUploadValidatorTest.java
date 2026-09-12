package ca.bazlur.threadcity.parser;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ThreadDumpUploadValidatorTest {

    private final ThreadDumpUploadValidator validator = new ThreadDumpUploadValidator();

    @Test
    void acceptsTextFilesAndRemovesUtf8Bom() {
        byte[] text = "\"worker\" #1\n".getBytes(StandardCharsets.UTF_8);
        byte[] withBom = new byte[text.length + 3];
        withBom[0] = (byte) 0xef;
        withBom[1] = (byte) 0xbb;
        withBom[2] = (byte) 0xbf;
        System.arraycopy(text, 0, withBom, 3, text.length);

        ThreadDumpUploadValidator.ValidatedUpload result = validator.validate("dump.txt", "text/plain", withBom);

        assertThat(result.sourceName()).isEqualTo("dump.txt");
        assertThat(result.content()).startsWith("\"worker\"").doesNotStartWith("\uFEFF");
    }

    @Test
    void acceptsTheMaximumSizeBoundary() {
        byte[] bytes = new byte[ThreadDumpUploadValidator.MAX_BYTES];
        Arrays.fill(bytes, (byte) 'x');

        assertThat(validator.validate("dump.log", "application/octet-stream", bytes).content())
                .hasSize(ThreadDumpUploadValidator.MAX_BYTES);
    }

    @Test
    void rejectsFilesAboveTheMaximumSize() {
        byte[] bytes = new byte[ThreadDumpUploadValidator.MAX_BYTES + 1];

        assertThatThrownBy(() -> validator.validate("dump.txt", "text/plain", bytes))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("5 MiB");
    }

    @Test
    void rejectsEmptyAndUnsupportedFiles() {
        assertThatThrownBy(() -> validator.validate("dump.txt", "text/plain", new byte[0]))
                .hasMessageContaining("empty");
        assertThatThrownBy(() -> validator.validate(
                "dump.zip", "application/zip", "text".getBytes(StandardCharsets.UTF_8)))
                .hasMessageContaining(".txt or .log");
    }

    @Test
    void rejectsMalformedUtf8AndBinaryLookingContent() {
        assertThatThrownBy(() -> validator.validate("dump.txt", "text/plain", new byte[]{(byte) 0xc3, 0x28}))
                .hasMessageContaining("UTF-8");
        assertThatThrownBy(() -> validator.validate(
                "dump.log", "text/plain", "thread\0data".getBytes(StandardCharsets.UTF_8)))
                .hasMessageContaining("binary");
    }

    @Test
    void rejectsExcessiveLineCounts() {
        byte[] bytes = "x\n".repeat(ThreadDumpUploadValidator.MAX_LINES).getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> validator.validate("dump.txt", "text/plain", bytes))
                .hasMessageContaining("too many lines");
    }

    @Test
    void sanitizesUploadedSourceNames() {
        ThreadDumpUploadValidator.ValidatedUpload result = validator.validate(
                "../private/dump.txt", "text/plain", "content".getBytes(StandardCharsets.UTF_8));

        assertThat(result.sourceName()).isEqualTo("dump.txt");
    }
}
