package jasmine.jragon.pdf;

import jasmine.jragon.LocalFileManager;
import jasmine.jragon.dropbox.model.v2.IntermediateFile;
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
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.apache.pdfbox.pdmodel.graphics.state.RenderingMode;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

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
    private static final float TEXT_ALPHA_VALUE = 0.0f;

    private static final int FONT_SIZE = 96;
    private static final int NUMBER_OF_LINES_PER_PAGE = 26;
    private static final int MAX_THREAD_ID = 100;

    private static final Random GENERATOR = new Random();

    private static final Set<Integer> THREAD_IDS = Collections.synchronizedSet(new TreeSet<>());

    private static final String LINE_OF_INVISIBLE_CHARS = "i".repeat(53);
    private static final String TEMPORARY_FILE_NAME = "temp-note-file-";
    private static final String WATERMARK_PDF = "Dorogan_Black-85.pdf";
    private static final String OWNER_PASSWORD;

    private static final PDExtendedGraphicsState TEXT_GRAPHICS_STATE;

    private static final Logger LOG = LoggerFactory.getLogger(PDFEditor.class);

    public static void customizeDocFile(IntermediateFile intermediateFile) {
        int pageCount = 1;
        boolean wasSuccessfulOperation;
        int threadId = generateThreadId();
        var tempFileName = createTempFileName(threadId);
        var root = new PDDocumentOutline();

        try (var document = intermediateFile.createPDF()) {
            document.getDocumentCatalog().setDocumentOutline(root);

            for (var page : document.getPages()) {
                addTextToPage(document, page);
                addOutlineToPage(page, pageCount++, root);
            }

            addMetaData(document);
            addPermissions(document, String.valueOf(intermediateFile.createLocalFileObject()));
            addWatermark(document, tempFileName);
            wasSuccessfulOperation = true;
        } catch (IOException | RuntimeException e) {
            LOG.error(e.getMessage(), e);
            wasSuccessfulOperation = false;
        }

        wasSuccessfulOperation = wasSuccessfulOperation &&
                LocalFileManager.attemptFileDeletion(intermediateFile.getLocalFile());

        if (wasSuccessfulOperation) {
            LocalFileManager.attemptFileRename(new File(tempFileName), intermediateFile.createLocalFileObject());
        } else {
            //Could delete the edited file and proceed with the original version
            LocalFileManager.attemptFileDeletion(tempFileName);
        }

        THREAD_IDS.remove(threadId);
    }

    private static String createTempFileName(int threadId) {
        return TEMPORARY_FILE_NAME + threadId + ".pdf";
    }
    
    private static int generateThreadId() {
        int threadId;
        do {
            threadId = GENERATOR.nextInt(MAX_THREAD_ID);
        } while (THREAD_IDS.contains(threadId));
        
        THREAD_IDS.add(threadId);
        return threadId;
    }

    private static void addTextToPage(PDDocument document, PDPage page) {
        try (var contentStream = new PDPageContentStream(document, page, APPEND, false)) {
            for (int i = 0; i < NUMBER_OF_LINES_PER_PAGE; i++) {
                contentStream.beginText();
                contentStream.setGraphicsStateParameters(TEXT_GRAPHICS_STATE);
                contentStream.setFont(new PDType1Font(Standard14Fonts.FontName.TIMES_ROMAN), FONT_SIZE);
                contentStream.newLineAtOffset(X_OFFSET, Y_OFFSET + (LINE_OFFSET * i));
                contentStream.showText(LINE_OF_INVISIBLE_CHARS);
                contentStream.endText();
            }
        } catch (IOException e) {
            LOG.error("IO Exception occurred on a page", e);
        }
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

    private static void addWatermark(PDDocument document, String tempFileName) throws RuntimeException {
        var watermarkStreamOptional = Optional.ofNullable(PDFEditor.class.getResourceAsStream(WATERMARK_PDF));

        if (watermarkStreamOptional.isEmpty()) {
            throw new RuntimeException("No watermark document");
        }

        try (var watermarkDocument = Loader.loadPDF(new RandomAccessReadBuffer(watermarkStreamOptional.get()));
             var overlay = new Overlay()) {

            overlay.setInputPDF(document);
            overlay.setOverlayPosition(Overlay.Position.FOREGROUND);
            overlay.setDefaultOverlayPDF(watermarkDocument);

            try (var outputDocument = overlay.overlayDocuments(new HashMap<>())) {
                outputDocument.save(tempFileName);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
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
                Objects.requireNonNull(PDFEditor.class.getResourceAsStream("pass.bin"))))) {
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
                    LOG.debug("Password loaded: {}*** - {} characters long",
                            OWNER_PASSWORD.substring(0, 3), OWNER_PASSWORD.length());
                    break;
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
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
