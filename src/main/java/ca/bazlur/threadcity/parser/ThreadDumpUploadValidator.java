package ca.bazlur.threadcity.parser;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Pattern;

public final class ThreadDumpUploadValidator {

    public static final int MAX_BYTES = 5 * 1024 * 1024;
    public static final int MAX_LINES = 100_000;
    private static final Pattern CONTROL_CHARACTER = Pattern.compile("[\\p{Cntrl}]");

    public ValidatedUpload validate(String fileName, String contentType, byte[] bytes) {
        if (!isAcceptedType(fileName, contentType)) {
            throw new IllegalArgumentException("Choose a .txt or .log thread dump");
        }
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("The uploaded file is empty");
        }
        if (bytes.length > MAX_BYTES) {
            throw new IllegalArgumentException("The uploaded file exceeds the 5 MiB limit");
        }

        int offset = hasUtf8Bom(bytes) ? 3 : 0;
        String content = decodeUtf8(bytes, offset);
        if (content.isBlank()) {
            throw new IllegalArgumentException("The uploaded file is empty");
        }
        rejectBinaryLookingContent(content);
        rejectTooManyLines(content);
        return new ValidatedUpload(safeSourceName(fileName), content);
    }

    public String validateUtf8Text(byte[] bytes, int maxBytes, int maxLines, String evidenceLabel) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException(evidenceLabel + " is empty");
        }
        if (bytes.length > maxBytes) {
            throw new IllegalArgumentException(evidenceLabel + " exceeds its size limit");
        }
        int offset = hasUtf8Bom(bytes) ? 3 : 0;
        String content = decodeUtf8(bytes, offset);
        if (content.isBlank()) {
            throw new IllegalArgumentException(evidenceLabel + " is empty");
        }
        rejectBinaryLookingContent(content);
        rejectTooManyLines(content, maxLines);
        return content;
    }

    private boolean isAcceptedType(String fileName, String contentType) {
        String normalizedName = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        String normalizedType = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        return normalizedName.endsWith(".txt")
                || normalizedName.endsWith(".log")
                || normalizedType.startsWith("text/plain");
    }

    private boolean hasUtf8Bom(byte[] bytes) {
        return bytes.length >= 3
                && (bytes[0] & 0xff) == 0xef
                && (bytes[1] & 0xff) == 0xbb
                && (bytes[2] & 0xff) == 0xbf;
    }

    private String decodeUtf8(byte[] bytes, int offset) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException("The uploaded file is not valid UTF-8 text");
        }
    }

    private void rejectBinaryLookingContent(String content) {
        int suspiciousControls = 0;
        for (int index = 0; index < content.length(); index++) {
            char character = content.charAt(index);
            if (character == '\0') {
                throw new IllegalArgumentException("The uploaded file appears to contain binary data");
            }
            if (Character.isISOControl(character)
                    && character != '\n'
                    && character != '\r'
                    && character != '\t'
                    && character != '\f') {
                suspiciousControls++;
            }
        }
        if (suspiciousControls > Math.max(4, content.length() / 100)) {
            throw new IllegalArgumentException("The uploaded file appears to contain binary data");
        }
    }

    private void rejectTooManyLines(String content) {
        rejectTooManyLines(content, MAX_LINES);
    }

    private void rejectTooManyLines(String content, int maxLines) {
        int lines = 1;
        char previous = 0;
        for (int index = 0; index < content.length(); index++) {
            char character = content.charAt(index);
            if (character == '\r' || (character == '\n' && previous != '\r')) {
                lines++;
                if (lines > maxLines) {
                    throw new IllegalArgumentException("The uploaded file contains too many lines");
                }
            }
            previous = character;
        }
    }

    private String safeSourceName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "Uploaded thread dump";
        }
        String normalized = fileName.replace('\\', '/');
        normalized = normalized.substring(normalized.lastIndexOf('/') + 1).strip();
        normalized = CONTROL_CHARACTER.matcher(normalized).replaceAll("?");
        if (normalized.isBlank()) {
            return "Uploaded thread dump";
        }
        return normalized.length() <= 120 ? normalized : normalized.substring(0, 117) + "...";
    }

    public record ValidatedUpload(String sourceName, String content) {
    }
}
