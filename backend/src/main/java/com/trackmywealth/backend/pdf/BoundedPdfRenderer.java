package com.trackmywealth.backend.pdf;

import java.io.IOException;
import java.util.List;
import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.contentstream.operator.OperatorName;
import org.apache.pdfbox.contentstream.operator.OperatorProcessor;
import org.apache.pdfbox.contentstream.operator.graphics.BeginInlineImage;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.rendering.PageDrawer;
import org.apache.pdfbox.rendering.PageDrawerParameters;

/**
 * A PDF renderer for OCR (#276) that runs each drawing operation only while its {@link
 * PdfReadBudget} allows, and checks every inline image ({@code BI ... ID ... EI} inside a content
 * stream) with {@link PdfStreamBudget#checkInlineImage} before PDFBox decodes it. An inline image
 * is no object of the document, so {@link BoundedPdfParser} never sees it, and PDFBox decodes its
 * whole data into memory as soon as the page drawer reaches it: a few kilobytes of Flate data can
 * inflate to gigabytes. The check runs wherever the drawer meets one - on a page, in a form, a
 * pattern or an annotation's appearance. A refused image is not drawn, and refuses the document
 * through the budget, which its caller checks after each page.
 */
public final class BoundedPdfRenderer extends PDFRenderer {

  private final PdfReadBudget budget;

  public BoundedPdfRenderer(PDDocument document, PdfReadBudget budget) {
    super(document);
    this.budget = budget;
  }

  @Override
  protected PageDrawer createPageDrawer(PageDrawerParameters parameters) throws IOException {
    return new BoundedPageDrawer(parameters, budget);
  }

  private static final class BoundedPageDrawer extends PageDrawer {

    private final PdfReadBudget budget;

    BoundedPageDrawer(PageDrawerParameters parameters, PdfReadBudget budget) throws IOException {
      super(parameters);
      this.budget = budget;
      // Replaces PDFBox's own BI operator, which it then calls once the check has passed.
      addOperator(new CheckedInlineImage(this, budget));
    }

    @Override
    protected void processOperator(Operator operator, List<COSBase> operands) throws IOException {
      if (budget.allowOperation()) {
        super.processOperator(operator, operands);
      }
    }
  }

  private static final class CheckedInlineImage extends OperatorProcessor {

    private final BeginInlineImage drawing;
    private final PdfReadBudget budget;

    CheckedInlineImage(PDFGraphicsStreamEngine engine, PdfReadBudget budget) {
      super(engine);
      this.drawing = new BeginInlineImage(engine);
      this.budget = budget;
    }

    @Override
    public void process(Operator operator, List<COSBase> operands) throws IOException {
      if (operator.getImageParameters() != null && operator.getImageData() != null) {
        try {
          PdfStreamBudget.checkInlineImage(operator.getImageParameters(), operator.getImageData());
        } catch (PdfLimitException e) {
          budget.refuse(e);
          return;
        }
      }
      drawing.process(operator, operands);
    }

    @Override
    public String getName() {
      return OperatorName.BEGIN_INLINE_IMAGE;
    }
  }
}
