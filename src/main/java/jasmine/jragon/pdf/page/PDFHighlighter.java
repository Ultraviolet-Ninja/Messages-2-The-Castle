package jasmine.jragon.pdf.page;

import lombok.NonNull;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.stream.Collectors;

public sealed interface PDFHighlighter permits HighlightableLinedPage {
    int INITIAL_CACHE_CAPACITY = 4;
    COSName FX_NAME = COSName.getPDFName("FXX1");

    /**
     * Lowercase 'i' appears to be the best character to allow for highlightable lines
     * on PDF pages since invisible characters don't allow for text boxes to appear
     */
    char INVISIBLE_CHARACTER = 'i';

    void generateHighlights(PDPageContentStream contentStream, PDExtendedGraphicsState state) throws IOException;

    static OptionalInt getBackgroundImageHash(@NonNull PDResources pageResources) {
        try {
            var fxObject = pageResources.getCOSObject()
                    .getCOSDictionary(COSName.XOBJECT)
                    .getCOSObject(FX_NAME)
                    .getObject();

            if (fxObject instanceof COSStream s) {
                try (var reader = new BufferedReader(new InputStreamReader(s.createRawInputStream()))) {
                    return reader.lines()
                            .collect(Collectors.collectingAndThen(
                                    Collectors.joining(),
                                    streamStr -> OptionalInt.of(streamStr.hashCode())
                            ));
                }
            }

            LoggerFactory.getLogger(HighlightableLinedPage.class)
                    .warn("Typecasting Issue - Received type {}", fxObject.getClass());
        } catch (IOException e) {
            LoggerFactory.getLogger(HighlightableLinedPage.class)
                    .warn("Stream Reader Error: ", e);
        } catch (RuntimeException e) {
            /*
             * It's either we catch the NullPointer if it ever were to occur
             * OR we do Optionals, but that's a lot of Optionals and
             * there's no null tracing in that case
             *
             * Otherwise, we can encounter a JSONException for mismatching syntax
             */
            LoggerFactory.getLogger(HighlightableLinedPage.class)
                    .warn("Onyx Image Stream Retrieval Issue: ", e);
        }

        return OptionalInt.empty();
    }

    static Optional<PDFHighlighter> fromImageHash(int imageHash) {
        return switch (imageHash) {
            case TwentySixLinedPage.IMAGE_CONTENT_HASH -> Optional.of(new TwentySixLinedPage());
            case TwentyFourLinedPage.IMAGE_CONTENT_HASH -> Optional.of(new TwentyFourLinedPage());
            case TwentyEightLinedPage.IMAGE_CONTENT_HASH -> Optional.of(new TwentyEightLinedPage());
            case ThirtyLinedPage.IMAGE_CONTENT_HASH -> Optional.of(new ThirtyLinedPage());
            default -> Optional.empty();
        };
    }

    static String generateCharLine(int characterCount) {
        var charArray = new char[characterCount];
        Arrays.fill(charArray, INVISIBLE_CHARACTER);
        return new String(charArray);
    }
}
