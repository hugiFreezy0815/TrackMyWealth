package com.trackmywealth.backend.pdf;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSObjectKey;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdfparser.PDFParser;
import org.apache.pdfbox.pdmodel.PDDocument;

/**
 * Loads a PDF with every stream checked against a {@link PdfStreamBudget} the moment it is parsed,
 * before PDFBox or anything else can decode it (#268, PR #267 review). A check after loading came
 * too late: loading itself decodes the cross-reference streams, and later the object streams, each
 * whole into memory, so a 300 KB file could exhaust the heap inside {@code Loader.loadPDF}.
 *
 * <p>Strict, never lenient: a lenient parse repairs a damaged file with a brute-force parser of its
 * own, which decodes object streams without passing through this one. Every bank statement sample
 * of the import source analysis loads strictly.
 */
public final class BoundedPdfParser extends PDFParser {

  private final PdfStreamBudget budget;
  // Streams parsed while a check resolved an indirect entry; checked once that check is done.
  private final Deque<COSStream> pending = new ArrayDeque<>();
  // How many checks are running: one resolving an indirect entry can parse a further stream.
  private int checks;
  // PDFBox logs and drops an exception of a lazily parsed object, so the refusal is kept here.
  private PdfLimitException refusal;

  private BoundedPdfParser(byte[] content, PdfStreamBudget budget) throws IOException {
    super(new RandomAccessReadBuffer(content));
    this.budget = budget;
  }

  /**
   * {@code content} loaded, with every object parsed and every stream within {@code budget}. An
   * encrypted document is returned with only its structure read: its caller refuses it.
   *
   * @throws PdfLimitException when a stream decodes to more than the budget allows
   * @throws IOException when it is not a PDF this strict parser reads
   */
  public static PDDocument load(byte[] content, PdfStreamBudget budget) throws IOException {
    BoundedPdfParser parser = new BoundedPdfParser(content, budget);
    PDDocument document = parser.parse(false);
    try {
      if (!document.isEncrypted()) {
        parser.parseEveryObject(document);
      }
      return document;
    } catch (IOException e) {
      document.close();
      throw e;
    }
  }

  // Parses each object now, through parseCOSStream, so nothing is left to parse lazily later.
  // Directly, not through COSObject.getObject(), which logs and drops any exception.
  private void parseEveryObject(PDDocument document) throws IOException {
    for (COSObjectKey key : List.copyOf(document.getDocument().getXrefTable().keySet())) {
      parseUnlessUnreadable(key);
      if (refusal != null) {
        throw refusal;
      }
    }
  }

  // An object PDFBox cannot parse is skipped, as its own lazy read would; being unreadable, it is
  // never decoded either. A stream over the budget refuses the file.
  private boolean parseUnlessUnreadable(COSObjectKey key) throws PdfLimitException {
    try {
      parseObjectDynamically(key, false);
      return true;
    } catch (PdfLimitException e) {
      throw e;
    } catch (IOException e) {
      return false;
    }
  }

  @Override
  protected COSStream parseCOSStream(COSDictionary dictionary) throws IOException {
    COSStream stream = super.parseCOSStream(dictionary);
    if (checks > 0) {
      pending.add(stream);
      return stream;
    }
    checks++;
    // Reading the stream's data, and resolving its indirect filter entries, moves the parser.
    long position = source.getPosition();
    try {
      budget.check(stream);
      while (!pending.isEmpty()) {
        budget.check(pending.poll());
      }
    } catch (PdfLimitException e) {
      refusal = e;
      throw e;
    } finally {
      checks--;
      pending.clear();
      source.seek(position);
    }
    return stream;
  }
}
