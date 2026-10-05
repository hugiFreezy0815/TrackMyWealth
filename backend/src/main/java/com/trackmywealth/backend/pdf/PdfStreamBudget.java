package com.trackmywealth.backend.pdf;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import javax.imageio.IIOException;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.apache.commons.io.function.IOSupplier;
import org.apache.commons.io.output.NullOutputStream;
import org.apache.commons.io.output.ThresholdingOutputStream;
import org.apache.commons.io.output.UnsynchronizedByteArrayOutputStream;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.filter.FilterFactory;

/**
 * The decoded bytes and image pixels one PDF may hold (#268, #276). PDFBox decodes a whole stream
 * into memory before it reads it, and a few hundred kilobytes of Flate data can inflate to
 * gigabytes, so every stream is first decoded once into a counter that keeps nothing: at most
 * {@value #MAX_STREAM_BYTES} bytes per stream and {@value #MAX_DECODED_BYTES} in all, and an image
 * of at most {@value #MAX_IMAGE_PIXELS} pixels - those it declares and, when it is rendered, for
 * JPEG and CCITT data those its own data or decode parameters hold, which is what PDFBox allocates.
 * One budget belongs to one read of one document; {@link BoundedPdfParser} checks each stream as it
 * parses it.
 *
 * <p>It also bounds how many objects the document declares, at most {@value #MAX_OBJECTS} in all
 * (fifth PR #267 review). A few bytes per entry of a cross-reference stream, or of an object
 * stream, declare one object, and PDFBox keeps several objects in memory for each: a 24 KB file
 * whose cross-reference stream declared 2.7 million objects, within the stream limits, exhausted a
 * 512 MB heap while loading. The objects of an object stream are counted as it is parsed, those of
 * a cross-reference stream before PDFBox reads its first entry, across every section of the file.
 */
public final class PdfStreamBudget {

  // A statement's largest stream (an embedded font, a page's content) is well under a megabyte.
  public static final int MAX_STREAM_BYTES = 16 * 1024 * 1024;
  public static final long MAX_DECODED_BYTES = 64L * 1024 * 1024;
  public static final long MAX_IMAGE_PIXELS = 50_000_000L;
  // A statement's streams use one or two filters; the chain is decoded one stage per call.
  public static final int MAX_FILTERS = 8;
  // The import source analysis' samples declare at most about 300 objects each.
  public static final long MAX_OBJECTS = 20_000;
  // Image codecs are decoded to pixels, bounded by MAX_IMAGE_PIXELS; the filters before one count.
  // PDFBox accepts the abbreviated names (meant for inline images) on any stream. JPX and JBIG2
  // need ImageIO plugins this application does not ship, so PDFBox cannot decode them at all.
  private static final Set<COSName> JPEG_CODECS =
      Set.of(COSName.DCT_DECODE, COSName.DCT_DECODE_ABBREVIATION);
  private static final Set<COSName> CCITT_CODECS =
      Set.of(COSName.CCITTFAX_DECODE, COSName.CCITTFAX_DECODE_ABBREVIATION);
  private static final Set<COSName> IMAGE_CODECS =
      Set.of(
          COSName.DCT_DECODE,
          COSName.DCT_DECODE_ABBREVIATION,
          COSName.JPX_DECODE,
          COSName.JBIG2_DECODE,
          COSName.CCITTFAX_DECODE,
          COSName.CCITTFAX_DECODE_ABBREVIATION);
  // PDFBox's default width of CCITT data without a /Columns decode parameter.
  private static final int CCITT_DEFAULT_COLUMNS = 1728;

  private final boolean rendered;
  private long decoded;
  private long objects;

  /**
   * @param rendered whether the document's pages are rendered (OCR), so its images are decoded too;
   *     reading the text layer decodes no image
   */
  public PdfStreamBudget(boolean rendered) {
    this.rendered = rendered;
  }

  /**
   * Counts {@code stream} against this budget before anything decodes it.
   *
   * @throws PdfLimitException when it, or the document so far, decodes to more than allowed
   */
  public void check(COSStream stream) throws IOException {
    countObjects(declaredObjects(stream));
    int limit = (int) Math.min(MAX_STREAM_BYTES, MAX_DECODED_BYTES - decoded);
    decoded +=
        boundedLength(
            stream,
            stream.getFilters(),
            COSName.IMAGE.equals(stream.getCOSName(COSName.SUBTYPE)),
            rendered,
            stream::createRawInputStream,
            stream.getLength(),
            limit);
  }

  /**
   * Checks the size of a loaded document's cross-reference table. It holds the objects every
   * cross-reference stream declared, already counted as they were parsed, and those of plain
   * cross-reference tables, which no stream declares (each line of one is a 20-byte entry, so the
   * upload limit bounds their memory, but not the work of parsing every object).
   *
   * @throws PdfLimitException when the table holds more objects than allowed
   */
  public static void checkObjectTable(int entries) throws PdfLimitException {
    if (entries > MAX_OBJECTS) {
      throw tooManyObjects();
    }
  }

  private void countObjects(long count) throws PdfLimitException {
    objects += count;
    if (objects > MAX_OBJECTS) {
      throw tooManyObjects();
    }
  }

  private static PdfLimitException tooManyObjects() {
    return new PdfLimitException("The PDF declares more objects than a statement needs.");
  }

  // The objects a cross-reference stream (its /Index ranges, by default 0 to /Size) or an object
  // stream (/N) declares; none for any other stream. A negative or non-numeric count declares none,
  // and PDFBox refuses the stream itself.
  private static long declaredObjects(COSStream stream) {
    COSName type = stream.getCOSName(COSName.TYPE);
    if (COSName.OBJ_STM.equals(type)) {
      return Math.max(0, stream.getInt(COSName.N, 0));
    }
    if (!COSName.XREF.equals(type)) {
      return 0;
    }
    if (!(stream.getDictionaryObject(COSName.INDEX) instanceof COSArray index)) {
      return Math.max(0, stream.getInt(COSName.SIZE, 0));
    }
    long declared = 0;
    // Pairs of first object number and count.
    for (int i = 1; i < index.size(); i += 2) {
      if (index.getObject(i) instanceof COSInteger count) {
        declared += Math.max(0, count.longValue());
      }
    }
    return declared;
  }

  /**
   * Checks an inline image ({@code BI ... ID ... EI}, inside a content stream, so no stream of its
   * own) like an image stream, before PDFBox decodes it.
   *
   * @param parameters the image's dictionary (abbreviated keys such as {@code /W}, {@code /F})
   * @param data its raw, still encoded data
   * @throws PdfLimitException when it decodes to more than allowed
   */
  public static void checkInlineImage(COSDictionary parameters, byte[] data) throws IOException {
    boundedLength(
        parameters,
        parameters.getDictionaryObject(COSName.F, COSName.FILTER),
        true,
        true,
        () -> new ByteArrayInputStream(data),
        data.length,
        MAX_STREAM_BYTES);
  }

  // The bytes the filters produce, up to the first image codec, counted and discarded; a
  // PdfLimitException as soon as they pass limit. An image is bounded by its pixels instead: those
  // it declares and, when it is rendered, those its CCITT decode parameters make PDFBox allocate
  // and those its JPEG data holds.
  private static long boundedLength(
      COSDictionary dictionary,
      COSBase filterEntry,
      boolean image,
      boolean rendered,
      IOSupplier<InputStream> raw,
      long rawLength,
      int limit)
      throws IOException {
    long height = dictionary.getInt(COSName.HEIGHT, COSName.H, 0);
    if (image) {
      requirePixels((long) dictionary.getInt(COSName.WIDTH, COSName.W, 0) * height);
    }
    List<COSName> filters = filterNames(filterEntry);
    if (filters.size() > MAX_FILTERS) {
      throw new PdfLimitException("A stream of the PDF has more filters than a statement needs.");
    }
    int stages = 0;
    while (stages < filters.size() && !IMAGE_CODECS.contains(filters.get(stages))) {
      stages++;
    }
    // The image codec the counted stages lead to; only a rendered one is decoded at all.
    COSName codec = rendered && stages < filters.size() ? filters.get(stages) : COSName.NONE;
    if (CCITT_CODECS.contains(codec)) {
      requireCcittPixels(dictionary, stages, height);
    }
    boolean jpeg = JPEG_CODECS.contains(codec);
    if (stages == 0 && !jpeg) {
      return rawLength;
    }
    try (InputStream input = raw.get()) {
      if (stages == 0) {
        requireJpegPixels(input);
        return rawLength;
      }
      return decodedLength(dictionary, filters.subList(0, stages), 0, input, limit, jpeg);
    }
  }

  // Decodes stage and every later one of filters from input. Only an intermediate stage keeps its
  // output, which the next stage reads in place rather than from a copy; the last one only counts,
  // unless JPEG data follows, whose header it then reads.
  private static long decodedLength(
      COSDictionary dictionary,
      List<COSName> filters,
      int stage,
      InputStream input,
      int limit,
      boolean jpegFollows)
      throws IOException {
    boolean last = stage == filters.size() - 1;
    boolean keep = !last || jpegFollows;
    try (UnsynchronizedByteArrayOutputStream kept =
            UnsynchronizedByteArrayOutputStream.builder().get();
        ThresholdingOutputStream counter =
            new ThresholdingOutputStream(
                limit,
                exceeded -> {
                  throw new PdfLimitException("A stream of the PDF decodes to more than allowed.");
                },
                exceeded -> keep ? kept : NullOutputStream.INSTANCE)) {
      FilterFactory.INSTANCE
          .getFilter(filters.get(stage))
          .decode(input, counter, dictionary, stage);
      if (last) {
        if (jpegFollows) {
          try (InputStream jpeg = kept.toInputStream()) {
            requireJpegPixels(jpeg);
          }
        }
        return counter.getByteCount();
      }
      try (InputStream next = kept.toInputStream()) {
        return decodedLength(dictionary, filters, stage + 1, next, limit, jpegFollows);
      }
    }
  }

  // PDFBox allocates Columns x max(Rows, Height) bits for CCITT data, whatever the image declares.
  private static void requireCcittPixels(COSDictionary dictionary, int stage, long height)
      throws IOException {
    COSBase parameters = dictionary.getDictionaryObject(COSName.DECODE_PARMS, COSName.DP);
    if (parameters instanceof COSArray array && stage < array.size()) {
      parameters = array.getObject(stage);
    }
    COSDictionary decode = parameters instanceof COSDictionary given ? given : new COSDictionary();
    long columns = decode.getInt(COSName.COLUMNS, CCITT_DEFAULT_COLUMNS);
    long rows = Math.max(decode.getInt(COSName.ROWS, 0), height);
    if (columns <= 0 || rows < 0) {
      throw new IOException("An image of the PDF has an invalid size.");
    }
    requirePixels(columns * rows);
  }

  // The size in the JPEG header, read without decoding the image and only in memory. Data the
  // JPEG reader cannot even parse a header of, PDFBox (which decodes with the same reader) cannot
  // decode either, so it holds no image to bound.
  private static void requireJpegPixels(InputStream data) throws IOException {
    Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("jpeg");
    if (!readers.hasNext()) {
      return;
    }
    ImageReader reader = readers.next();
    try (MemoryCacheImageInputStream input = new MemoryCacheImageInputStream(data)) {
      reader.setInput(input, true, true);
      requirePixels((long) reader.getWidth(0) * reader.getHeight(0));
    } catch (IIOException ignored) {
      // No header to read: see above.
    } finally {
      reader.dispose();
    }
  }

  private static void requirePixels(long pixels) throws PdfLimitException {
    if (pixels > MAX_IMAGE_PIXELS) {
      throw new PdfLimitException("An image of the PDF has more pixels than a statement needs.");
    }
  }

  private static List<COSName> filterNames(COSBase filters) {
    if (filters instanceof COSName name) {
      return List.of(name);
    }
    List<COSName> names = new ArrayList<>();
    if (filters instanceof COSArray array) {
      for (COSBase entry : array) {
        if (entry instanceof COSName name) {
          names.add(name);
        }
      }
    }
    return names;
  }
}
