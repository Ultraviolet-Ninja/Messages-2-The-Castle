package jasmine.jragon.pdf.page;

import lombok.NonNull;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.json.JSONObject;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Arrays;
import java.util.Optional;

public sealed interface PDFHighlighter permits HighlightableLinedPage {
    int INITIAL_CACHE_CAPACITY = 4;
    COSName FX_NAME = COSName.getPDFName("FXX1");
    String ONYX_TAG = "onyxtag";
    String JSON_RESOURCE_KEY = "resource_id";
    String JSON_ID_KEY = "id";
    String JSON_ATTRIBUTES_KEY = "extra_attr";

    /**
     * Lowercase 'i' appears to be the best character to allow for highlightable lines
     * on PDF pages since invisible characters don't allow for text boxes to appear
     */
    char INVISIBLE_CHARACTER = 'i';

    void generateHighlights(PDPageContentStream contentStream, PDExtendedGraphicsState state) throws IOException;

    static Optional<JSONObject> getToImageOnyxTag(@NonNull PDResources pageResources) {
        try {
            var fxObject = pageResources.getCOSObject()
                    .getCOSDictionary(COSName.XOBJECT)
                    .getCOSObject(FX_NAME)
                    .getObject();

            if (fxObject instanceof COSDictionary d) {
                return Optional.of(new JSONObject(d.getString(ONYX_TAG)));
            }

            LoggerFactory.getLogger(HighlightableLinedPage.class)
                    .warn("Typecasting Issue - Received type {}", fxObject.getClass());
        } catch (RuntimeException e) {
            /*
             * It's either we catch the NullPointer if it ever were to occur
             * OR we do Optionals, but that's a lot of Optionals and
             * there's no null tracing in that case
             *
             * Otherwise, we can encounter a JSONException for mismatching syntax
             */
            LoggerFactory.getLogger(HighlightableLinedPage.class)
                    .warn("Onyx Tag Retrieval Issue: ", e);
        }
        return Optional.empty();
    }

    static Optional<PDFHighlighter> fromImageId(int imageId) {
        return switch (imageId) {
            case TwentySixLinedPage.IMAGE_ID -> Optional.of(new TwentySixLinedPage());
            case TwentyFourLinedPage.IMAGE_ID -> Optional.of(new TwentyFourLinedPage());
            case TwentyEightLinedPage.IMAGE_ID -> Optional.of(new TwentyEightLinedPage());
            case ThirtyLinedPage.IMAGE_ID -> Optional.of(new ThirtyLinedPage());
            default -> Optional.empty();
        };
    }

    static String generateCharLine(int characterCount) {
        var charArray = new char[characterCount];
        Arrays.fill(charArray, INVISIBLE_CHARACTER);
        return new String(charArray);
    }
}
