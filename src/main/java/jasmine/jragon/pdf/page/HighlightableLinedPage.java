package jasmine.jragon.pdf.page;

import lombok.RequiredArgsConstructor;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;

import java.io.IOException;
import java.util.stream.IntStream;

@RequiredArgsConstructor
public sealed abstract class HighlightableLinedPage implements PDFHighlighter
        permits TwentyEightLinedPage, ThirtyLinedPage, TwentyFourLinedPage, TwentySixLinedPage {
    private static final int BASE_X_OFFSET = 10, BASE_Y_OFFSET = 35;

    private final int numberOfLines, fontSize, characterCount;
    private final float lineOffset;

    @Override
    public final void generateHighlights(PDPageContentStream contentStream, PDExtendedGraphicsState state) throws IOException {
        var invisibleLine = PDFHighlighter.generateCharLine(characterCount);

        var yLevels = IntStream.range(0, numberOfLines)
                .mapToDouble(i  -> (i * lineOffset) + BASE_Y_OFFSET)
                .toArray();

        for (double yLevel : yLevels) {
            contentStream.beginText();
            contentStream.setGraphicsStateParameters(state);
            contentStream.setFont(new PDType1Font(Standard14Fonts.FontName.TIMES_ROMAN), fontSize);
            contentStream.newLineAtOffset(BASE_X_OFFSET, (float) yLevel);
            contentStream.showText(invisibleLine);
            contentStream.endText();
        }
    }
}
