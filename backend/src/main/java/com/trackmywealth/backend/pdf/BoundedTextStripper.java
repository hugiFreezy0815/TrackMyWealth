package com.trackmywealth.backend.pdf;

import java.io.IOException;
import java.util.List;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.text.PDFTextStripper;

/**
 * A text stripper that runs each drawing operation only while its {@link PdfReadBudget} allows
 * (#268): reading the text layer of a page runs its content stream, and every form it draws, again
 * for each time it is drawn. Its caller checks the budget after each page.
 */
public final class BoundedTextStripper extends PDFTextStripper {

  private final PdfReadBudget budget;

  public BoundedTextStripper(PdfReadBudget budget) {
    super();
    this.budget = budget;
  }

  @Override
  protected void processOperator(Operator operator, List<COSBase> operands) throws IOException {
    if (budget.allowOperation()) {
      super.processOperator(operator, operands);
    }
  }
}
