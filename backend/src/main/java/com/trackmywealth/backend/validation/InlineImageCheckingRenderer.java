package com.trackmywealth.backend.validation;

import java.io.IOException;
import java.util.List;
import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.contentstream.operator.OperatorName;
import org.apache.pdfbox.contentstream.operator.OperatorProcessor;
import org.apache.pdfbox.contentstream.operator.graphics.BeginInlineImage;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.rendering.PageDrawer;
import org.apache.pdfbox.rendering.PageDrawerParameters;

/**
 * A PDF renderer that checks every inline image ({@code BI ... ID ... EI} inside a content stream)
 * before PDFBox decodes it (#276, PDF statements read by OCR). An inline image is no object of the
 * document, so a check of the document's streams never sees it, and PDFBox decodes its whole data
 * into memory as soon as the page drawer reaches it: a few kilobytes of Flate data can inflate to
 * gigabytes. The check runs first, wherever the drawer meets one - on a page, in a form, a pattern
 * or an annotation's appearance.
 */
public final class InlineImageCheckingRenderer extends PDFRenderer {

  /** The check of one inline image, before it is decoded. */
  @FunctionalInterface
  public interface InlineImageCheck {

    /**
     * @param parameters the image's dictionary (abbreviated keys such as {@code /W}, {@code /F})
     * @param data its raw, still encoded data
     * @throws IOException to refuse the image, and with it the page
     */
    void check(COSDictionary parameters, byte[] data) throws IOException;
  }

  private final InlineImageCheck check;

  public InlineImageCheckingRenderer(PDDocument document, InlineImageCheck check) {
    super(document);
    this.check = check;
  }

  @Override
  protected PageDrawer createPageDrawer(PageDrawerParameters parameters) throws IOException {
    return new CheckingPageDrawer(parameters, check);
  }

  private static final class CheckingPageDrawer extends PageDrawer {

    CheckingPageDrawer(PageDrawerParameters parameters, InlineImageCheck check) throws IOException {
      super(parameters);
      // Replaces PDFBox's own BI operator, which it then calls once the check has passed.
      addOperator(new CheckedInlineImage(this, check));
    }
  }

  private static final class CheckedInlineImage extends OperatorProcessor {

    private final BeginInlineImage drawing;
    private final InlineImageCheck check;

    CheckedInlineImage(PDFGraphicsStreamEngine engine, InlineImageCheck check) {
      super(engine);
      this.drawing = new BeginInlineImage(engine);
      this.check = check;
    }

    @Override
    public void process(Operator operator, List<COSBase> operands) throws IOException {
      if (operator.getImageParameters() != null && operator.getImageData() != null) {
        check.check(operator.getImageParameters(), operator.getImageData());
      }
      drawing.process(operator, operands);
    }

    @Override
    public String getName() {
      return OperatorName.BEGIN_INLINE_IMAGE;
    }
  }
}
