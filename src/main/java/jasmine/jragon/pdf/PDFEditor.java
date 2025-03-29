package jasmine.jragon.pdf;

import jasmine.jragon.LocalResourceManager;
import jasmine.jragon.dropbox.model.v2.IntermediateFile;
import jasmine.jragon.pdf.page.PDFHighlighter;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.multipdf.Overlay;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.apache.pdfbox.pdmodel.graphics.state.RenderingMode;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import speiger.src.collections.ints.maps.impl.concurrent.Int2ObjectConcurrentOpenHashMap;
import speiger.src.collections.ints.maps.interfaces.Int2ObjectMap;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

import static org.apache.pdfbox.pdmodel.PDPageContentStream.AppendMode.APPEND;

/*
 * Page Height: 1872.0 - Page Width: 1404.0
 * Page dimensions: 26" x 19.5"
 * 26 Lines per page
 */

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class PDFEditor {
    // Define text position
    private static final float X_OFFSET = 10, Y_OFFSET = 35;
    private static final float LINE_OFFSET = 69.41f;
    private static final int FONT_SIZE = 96;
    private static final String LINE_OF_INVISIBLE_CHARS = "i".repeat(53);
    private static final int NUMBER_OF_LINES_PER_PAGE = 26;

    private static final float TEXT_ALPHA_VALUE = 0.0f;

    private static final String TEMPORARY_FILE_NAME = "temp-note-file-";

    private static final Map<String, String> THREAD_FILENAME_CACHE = Collections.synchronizedMap(new TreeMap<>());

    private static final String WATERMARK_PDF = "Dorogan_Black-85.pdf";
    private static final String PASSWORD_FILE = "pass.bin";
    private static final String OWNER_PASSWORD;

    private static final PDExtendedGraphicsState TEXT_GRAPHICS_STATE;

    private static final Logger LOG = LoggerFactory.getLogger(PDFEditor.class);

    private static final Int2ObjectMap<PDFHighlighter> HIGHLIGHTER_CACHE =
            new Int2ObjectConcurrentOpenHashMap<>(PDFHighlighter.INITIAL_CACHE_CAPACITY);

    public static void customizeDocFile(IntermediateFile intermediateFile) {
        int pageCount = 1;
        boolean wasSuccessfulOperation;
        var tempFileName = createTempFileName();
        var docOutline = new PDDocumentOutline();
        boolean watermarkAdded = false;
        try (var document = intermediateFile.createPDF()) {
            document.getDocumentCatalog().setDocumentOutline(docOutline);

            for (var page : document.getPages()) {
                addTextToPage(document, page);
                addOutlineToPage(page, pageCount++, docOutline);
            }

            addMetaData(document);
            addPermissions(document, String.valueOf(intermediateFile.createLocalFileObject()));
            watermarkAdded = addWatermark(document, tempFileName);
            wasSuccessfulOperation = watermarkAdded;
        } catch (IOException | RuntimeException e) {
            LOG.error("Transaction Error Occurred on {}: ", intermediateFile.getDropboxFilePath(), e);
            wasSuccessfulOperation = false;
        }

        wasSuccessfulOperation = wasSuccessfulOperation &&
                LocalResourceManager.attemptFileDeletion(intermediateFile.getLocalFile()) &&
                LocalResourceManager.attemptFileRename(new File(tempFileName), intermediateFile.createLocalFileObject());;

        if (!wasSuccessfulOperation && watermarkAdded) {
            //Could delete the edited file and proceed with the original version
            LocalResourceManager.attemptFileDeletion(tempFileName);
        }
    }

    private static String createTempFileName() {
        var threadName = LocalResourceManager.getThreadName();

        if (threadName.startsWith("Fork")) {
            var forkJoinThreadId = threadName.substring(threadName.lastIndexOf('-') + 1);

            return THREAD_FILENAME_CACHE.computeIfAbsent(
                    forkJoinThreadId,
                    fjThread -> TEMPORARY_FILE_NAME + fjThread + ".pdf"
            );
        }

        return TEMPORARY_FILE_NAME + "0.pdf";
    }

    private static void addTextToPage(PDDocument document, PDPage page) {
//        try (var contentStream = new PDPageContentStream(document, page, APPEND, false)) {
//            for (int i = 0; i < NUMBER_OF_LINES_PER_PAGE; i++) {
//                contentStream.beginText();
//                contentStream.setGraphicsStateParameters(TEXT_GRAPHICS_STATE);
//                contentStream.setFont(new PDType1Font(Standard14Fonts.FontName.TIMES_ROMAN), FONT_SIZE);
//                contentStream.newLineAtOffset(X_OFFSET, Y_OFFSET + (LINE_OFFSET * i));
//                contentStream.showText(LINE_OF_INVISIBLE_CHARS);
//                contentStream.endText();
//            }
//        } catch (IOException e) {
//            LOG.error("IO Exception occurred on a page", e);
//        }
        var hashOptional = PDFHighlighter.getBackgroundImageHash(page.getResources());

        Optional<PDFHighlighter> highlighterOpt = hashOptional.isPresent() ?
                getHighlighter(hashOptional.getAsInt()) :
                Optional.empty();

        highlighterOpt.ifPresent(highlighter -> {
            try (var contentStream = new PDPageContentStream(document, page, APPEND, false)) {
                highlighter.generateHighlights(contentStream, TEXT_GRAPHICS_STATE);
            } catch (IOException e) {
                LOG.error("IO Exception occurred on a page", e);
            }
        });
    }

    private static Optional<PDFHighlighter> getHighlighter(int imageContentHash) {
        if (HIGHLIGHTER_CACHE.containsKey(imageContentHash)) {
            return Optional.of(HIGHLIGHTER_CACHE.get(imageContentHash));
        }
        var opt = PDFHighlighter.fromImageHash(imageContentHash);

        opt.ifPresentOrElse(
                pdfHighlighter -> HIGHLIGHTER_CACHE.put(imageContentHash, pdfHighlighter),
                () -> LOG.info("Hash value {} has no highlighter", imageContentHash)
        );

        return opt;
    }

    private static void addOutlineToPage(PDPage page, int pageNumber, PDOutlineNode outlineNode) {
        var outlineItem = new PDOutlineItem();
        outlineItem.setTitle(String.format("Page %d", pageNumber));
        outlineItem.setDestination(page);

        outlineNode.addLast(outlineItem);
    }

    private static void addMetaData(PDDocument document) {
        var documentInfo = document.getDocumentInformation();

        var dateTime = LocalDateTime.now();
        var currentCalendarDate = Calendar.getInstance();
        var month = dateTime.getMonthValue() - 1;

        //noinspection MagicConstant
        currentCalendarDate.set(dateTime.getYear(), month, dateTime.getDayOfMonth(),
                dateTime.getHour(), dateTime.getMinute(), dateTime.getSecond());
        documentInfo.setModificationDate(currentCalendarDate);
    }

    private static boolean addWatermark(PDDocument document, String tempFileName) throws IOException {
        var watermarkStreamOptional = Optional.ofNullable(PDFEditor.class.getResourceAsStream(WATERMARK_PDF));

        if (watermarkStreamOptional.isEmpty()) {
            LOG.warn("Watermark file not found. Aborting.");
            return false;
        }

        try (var watermarkDocument = Loader.loadPDF(new RandomAccessReadBuffer(watermarkStreamOptional.get()));
             var overlay = new Overlay()) {

            overlay.setInputPDF(document);
            overlay.setOverlayPosition(Overlay.Position.FOREGROUND);
            overlay.setDefaultOverlayPDF(watermarkDocument);

            try (var outputDocument = overlay.overlayDocuments(new HashMap<>())) {
                outputDocument.save(tempFileName);
                return true;
            }
        }
    }

    private static void addPermissions(PDDocument document, String filename) {
        var accessPermission = new AccessPermission();
        accessPermission.setCanPrint(false);
        accessPermission.setCanModify(false); // Disable modification
        accessPermission.setCanExtractContent(false); // Disable content extraction
        accessPermission.setCanFillInForm(false); // Allow form filling
        accessPermission.setCanModifyAnnotations(false); // Disable annotation modifications
        accessPermission.setCanAssembleDocument(false); // Disable document assembly

        // Create the standard protection policy object
        var protectionPolicy = new StandardProtectionPolicy(OWNER_PASSWORD, "", accessPermission);

        // Set the encryption key length
        protectionPolicy.setEncryptionKeyLength(256);

        // Apply the protection policy to the document
        try {
            document.protect(protectionPolicy);
        } catch (IOException e) {
            LOG.warn("Policy was not added to {}", filename);
        }
    }

    static {
        TEXT_GRAPHICS_STATE = new PDExtendedGraphicsState();
        TEXT_GRAPHICS_STATE.setNonStrokingAlphaConstant(TEXT_ALPHA_VALUE); // Set transparency (0.0f to 1.0f)
        TEXT_GRAPHICS_STATE.setAlphaSourceFlag(true);
        TEXT_GRAPHICS_STATE.setRenderingIntent(String.valueOf(RenderingMode.FILL));
    }

    static {
        try (var inputStream = new DataInputStream(new BufferedInputStream(
                Objects.requireNonNull(PDFEditor.class.getResourceAsStream(PASSWORD_FILE))))) {
            var stringBuilder = new StringBuilder();

            while (true) {
                try {
                    int letter = 0;
                    for (int i = 0; i < 8; i++) {
                        letter <<= 1;
                        letter += inputStream.readByte() - '0';
                    }
                    stringBuilder.append((char) letter);
                } catch (EOFException e) {
                    OWNER_PASSWORD = stringBuilder.toString();
                    LOG.trace("Password loaded: {}*** - {} characters long",
                            OWNER_PASSWORD.substring(0, 3), OWNER_PASSWORD.length());
                    break;
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

//    static {
//        if (System.getProperty("os.name").toLowerCase().contains("linux")) {
//            LINE_OF_INVISIBLE_CHARS = "i".repeat(48);
//            FONT_SIZE = 102;
//            X_OFFSET = 30;
//            LINE_OFFSET = 75.25f;
//            NUMBER_OF_LINES_PER_PAGE = 24; //Don't know why this is happening on Linux, but whatever
//        } else {
//            LINE_OF_INVISIBLE_CHARS = "i".repeat(50);
//            FONT_SIZE = 96;
//            X_OFFSET = 50;
//            LINE_OFFSET = 69.41f;
//            NUMBER_OF_LINES_PER_PAGE = 26;
//        }
//    }
}
