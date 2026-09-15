package ca.bazlur.threadcity.ui.component;

import ca.bazlur.threadcity.application.IncidentBundleService;
import ca.bazlur.threadcity.parser.ThreadDumpUploadValidator;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.upload.Upload;
import com.vaadin.flow.server.streams.InMemoryUploadHandler;
import com.vaadin.flow.server.streams.UploadMetadata;

import java.util.Locale;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Owns the two bounded evidence-upload controls shown in the hero. */
public final class EvidenceUploadControls {

    private final Consumer<String> errorNotifier;
    private final Upload dumpUpload;
    private final Upload bundleUpload;
    private final Div dumpCard;
    private final Div bundleCard;

    public EvidenceUploadControls(
            BiConsumer<UploadMetadata, byte[]> dumpHandler,
            BiConsumer<UploadMetadata, byte[]> bundleHandler,
            Consumer<String> errorNotifier) {
        this.errorNotifier = errorNotifier;
        dumpUpload = createDumpUpload(dumpHandler);
        bundleUpload = createBundleUpload(bundleHandler);
        dumpCard = buildDumpCard();
        bundleCard = buildBundleCard();
    }

    public Component dumpCard() {
        return dumpCard;
    }

    public Component bundleCard() {
        return bundleCard;
    }

    public void setEnabled(boolean enabled) {
        dumpUpload.setEnabled(enabled);
        bundleUpload.setEnabled(enabled);
    }

    public void clearDump() {
        dumpUpload.clearFileList();
    }

    public void clearBundle() {
        bundleUpload.clearFileList();
    }

    public void clear() {
        clearDump();
        clearBundle();
    }

    private Upload createDumpUpload(BiConsumer<UploadMetadata, byte[]> uploadHandler) {
        InMemoryUploadHandler handler = new InMemoryUploadHandler(uploadHandler::accept) {
            @Override
            public long getFileSizeMax() {
                return ThreadDumpUploadValidator.MAX_BYTES;
            }

            @Override
            public long getRequestSizeMax() {
                return ThreadDumpUploadValidator.MAX_BYTES + 64 * 1024L;
            }

            @Override
            public long getFileCountMax() {
                return 1;
            }
        };
        handler.whenComplete(success -> {
            if (!success) {
                errorNotifier.accept("Upload failed. The file was not retained.");
            }
        });

        Upload upload = new Upload(handler);
        upload.setMaxFiles(1);
        upload.setMaxFileSize(ThreadDumpUploadValidator.MAX_BYTES);
        upload.setAcceptedFileExtensions(".txt", ".log");
        upload.setAcceptedMimeTypes("text/plain");
        upload.setDropLabel(new Span("Drop a jstack .txt or .log here"));
        Button chooseFile = new Button("Analyze your thread dump");
        chooseFile.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        upload.setUploadButton(chooseFile);
        upload.addClassName("dump-upload");
        upload.addFileRejectedListener(event -> errorNotifier.accept(normalizeDumpError(event.getErrorMessage())));
        return upload;
    }

    private Upload createBundleUpload(BiConsumer<UploadMetadata, byte[]> uploadHandler) {
        InMemoryUploadHandler handler = new InMemoryUploadHandler(uploadHandler::accept) {
            @Override
            public long getFileSizeMax() {
                return IncidentBundleService.MAX_BUNDLE_BYTES;
            }

            @Override
            public long getRequestSizeMax() {
                return IncidentBundleService.MAX_BUNDLE_BYTES + 64 * 1024L;
            }

            @Override
            public long getFileCountMax() {
                return 1;
            }
        };
        handler.whenComplete(success -> {
            if (!success) {
                errorNotifier.accept("Incident bundle upload failed. The file was not retained.");
            }
        });

        Upload upload = new Upload(handler);
        upload.setMaxFiles(1);
        upload.setMaxFileSize(IncidentBundleService.MAX_BUNDLE_BYTES);
        upload.setAcceptedFileExtensions(".threadcity");
        upload.setAcceptedMimeTypes("application/zip", "application/octet-stream");
        upload.setDropLabel(new Span("Drop a collector .threadcity bundle"));
        Button chooseFile = new Button("Open complete incident bundle");
        chooseFile.addThemeVariants(ButtonVariant.LUMO_CONTRAST);
        upload.setUploadButton(chooseFile);
        upload.addClassName("bundle-upload");
        upload.addFileRejectedListener(event ->
                errorNotifier.accept("Choose one .threadcity bundle up to 48 MiB"));
        return upload;
    }

    private Div buildDumpCard() {
        Div card = new Div(dumpUpload);
        card.addClassName("upload-card");
        Span privacy = new Span("UTF-8 text only · 5 MiB maximum · Released when you clear the analysis");
        privacy.addClassName("upload-privacy");
        Anchor example = new Anchor("/examples/jstack.txt", "Download example jstack.txt");
        example.getElement().setAttribute("download", "jstack.txt");
        example.addClassName("example-download");
        Div footer = new Div(privacy, example);
        footer.addClassName("upload-footer");
        card.add(footer);
        return card;
    }

    private Div buildBundleCard() {
        Span label = new Span("FULL INCIDENT WINDOW");
        label.addClassName("bundle-upload-label");
        Div card = new Div(
                label,
                new Paragraph("Open 2–5 chronological dumps, Java 25 all-thread evidence, and JFR in one step."),
                bundleUpload);
        card.addClassNames("upload-card", "bundle-upload-card");
        return card;
    }

    private static String normalizeDumpError(String message) {
        if (message == null || message.isBlank()) {
            return "The file was rejected. Use a .txt or .log file up to 5 MiB.";
        }
        String normalized = message.toLowerCase(Locale.ROOT);
        if (normalized.contains("large") || normalized.contains("size")) {
            return "The uploaded file exceeds the 5 MiB limit";
        }
        if (normalized.contains("type") || normalized.contains("format")) {
            return "Choose a .txt or .log thread dump";
        }
        return "The file was rejected. Use a .txt or .log file up to 5 MiB.";
    }
}
