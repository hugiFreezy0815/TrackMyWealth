package com.trackmywealth.backend.camt;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * #350: reads a CAMT import file - one XML document, or a ZIP archive of them as German online
 * banking downloads them - into its messages, in file order.
 *
 * <p>Whether a file is one is told by its bytes alone, never by its name or media type: XML starts
 * with {@code <} (after a byte order mark and blanks), a ZIP archive with its local file header.
 *
 * <p>An archive is untrusted like the XML in it: at most {@value #MAX_ARCHIVE_ENTRIES} entries,
 * each inflating to at most {@value #MAX_ENTRY_BYTES} bytes and {@value #MAX_COMPRESSION_RATIO}
 * times its compressed size, all of them to at most {@value #MAX_INFLATED_BYTES} bytes, each
 * counted while it inflates, so a ZIP bomb stops at the limit. Each entry is read in memory, one at
 * a time; its name is never used, as a path or otherwise. Every entry that is not a directory must
 * be XML.
 */
public final class CamtFileReader {

  /**
   * The upload limit ({@code spring.servlet.multipart.max-file-size}): a stored file is no larger.
   */
  public static final int MAX_FILE_BYTES = 5 * 1024 * 1024;

  static final int MAX_ARCHIVE_ENTRIES = 50;
  static final int MAX_ENTRY_BYTES = MAX_FILE_BYTES;
  static final long MAX_INFLATED_BYTES = 4L * MAX_FILE_BYTES;
  // XML statements compress about tenfold, highly repetitive ones some 30 times.
  static final int MAX_COMPRESSION_RATIO = 100;

  private static final int SNIFF_BYTES = 1024;
  private static final int BUFFER_BYTES = 8192;
  private static final byte LESS_THAN = '<';
  private static final byte[] UTF_8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
  private static final byte[] UTF_16_BE_BOM = {(byte) 0xFE, (byte) 0xFF};
  private static final byte[] UTF_16_LE_BOM = {(byte) 0xFF, (byte) 0xFE};
  private static final byte[] ZIP_ENTRY = {'P', 'K', 3, 4};
  private static final byte[] ZIP_EMPTY = {'P', 'K', 5, 6};

  private CamtFileReader() {}

  /** Whether {@code content} is XML or a ZIP archive, so that it can only be a CAMT file. */
  public static boolean isCandidate(byte[] content) {
    return isZip(content) || isXml(content);
  }

  /**
   * The messages of {@code content}: its one XML document, or each XML entry of its ZIP archive.
   *
   * @throws CamtUnsupportedException an XML document is no supported CAMT message
   * @throws CamtFormatException neither XML nor a readable ZIP archive of XML documents
   * @throws CamtLimitException the file holds more than one import may
   */
  public static List<CamtMessage> read(byte[] content)
      throws CamtFormatException, CamtLimitException {
    if (content.length > MAX_FILE_BYTES) {
      throw new CamtLimitException("The file is larger than " + MAX_FILE_BYTES + " bytes.");
    }
    CamtReadBudget budget = new CamtReadBudget();
    if (isZip(content)) {
      return readArchive(content, budget);
    }
    if (isXml(content)) {
      return List.of(CamtXmlReader.read(content, budget));
    }
    throw new CamtFormatException("The file is neither XML nor a ZIP archive.");
  }

  private static List<CamtMessage> readArchive(byte[] archive, CamtReadBudget budget)
      throws CamtFormatException, CamtLimitException {
    // Entry names are never used, so any bytes decode: a name that is not UTF-8 is no error.
    try (ZipInputStream zip =
        new ZipInputStream(new ByteArrayInputStream(archive), StandardCharsets.ISO_8859_1)) {
      return readEntries(zip, archive.length, budget);
    } catch (CamtFormatException | CamtLimitException e) {
      throw e;
    } catch (IOException e) {
      throw new CamtFormatException("The ZIP archive is damaged or encrypted.", e);
    }
  }

  private static List<CamtMessage> readEntries(
      ZipInputStream zip, int archiveLength, CamtReadBudget budget) throws IOException {
    List<CamtMessage> messages = new ArrayList<>();
    long inflated = 0;
    int entries = 0;
    for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
      entries++;
      if (entries > MAX_ARCHIVE_ENTRIES) {
        throw new CamtLimitException(
            "The ZIP archive holds more than " + MAX_ARCHIVE_ENTRIES + " entries.");
      }
      if (entry.isDirectory()) {
        continue;
      }
      byte[] xml = inflate(zip);
      inflated += xml.length;
      // The compressed size is known once the entry is read, from its header or data descriptor.
      long compressed = entry.getCompressedSize();
      if (inflated > MAX_INFLATED_BYTES
          || inflated > (long) MAX_COMPRESSION_RATIO * archiveLength
          || compressed > 0 && xml.length > MAX_COMPRESSION_RATIO * compressed) {
        throw new CamtLimitException("The ZIP archive inflates further than one import may hold.");
      }
      if (!isXml(xml)) {
        throw new CamtFormatException("The ZIP archive holds a file that is not XML.");
      }
      messages.add(CamtXmlReader.read(xml, budget));
    }
    if (messages.isEmpty()) {
      throw new CamtFormatException("The ZIP archive holds no XML statement.");
    }
    return messages;
  }

  // The current entry's bytes, counted while they inflate: never more than MAX_ENTRY_BYTES.
  private static byte[] inflate(ZipInputStream zip) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buffer = new byte[BUFFER_BYTES];
    for (int read = zip.read(buffer); read >= 0; read = zip.read(buffer)) {
      if (out.size() + read > MAX_ENTRY_BYTES) {
        throw new CamtLimitException(
            "A ZIP archive entry inflates to more than " + MAX_ENTRY_BYTES + " bytes.");
      }
      out.write(buffer, 0, read);
    }
    return out.toByteArray();
  }

  private static boolean isZip(byte[] content) {
    return startsWith(content, ZIP_ENTRY) || startsWith(content, ZIP_EMPTY);
  }

  // Starts with "<" after an optional byte order mark (UTF-8, UTF-16) and blanks.
  static boolean isXml(byte[] content) {
    int start = 0;
    boolean wide = false;
    boolean bigEndian = false;
    if (startsWith(content, UTF_8_BOM)) {
      start = UTF_8_BOM.length;
    } else if (startsWith(content, UTF_16_BE_BOM) || startsWith(content, UTF_16_LE_BOM)) {
      start = UTF_16_BE_BOM.length;
      wide = true;
      bigEndian = startsWith(content, UTF_16_BE_BOM);
    }
    int width = wide ? 2 : 1;
    int end = Math.min(content.length, SNIFF_BYTES);
    for (int at = start; at + width <= end; at += width) {
      int low = wide && bigEndian ? at + 1 : at;
      if (wide && content[bigEndian ? at : at + 1] != 0) {
        return false;
      }
      if (content[low] == LESS_THAN) {
        return true;
      }
      if (!isBlank(content[low])) {
        return false;
      }
    }
    return false;
  }

  private static boolean isBlank(byte value) {
    return switch (value) {
      case ' ', '\t', '\r', '\n' -> true;
      default -> false;
    };
  }

  private static boolean startsWith(byte[] content, byte[] prefix) {
    if (content.length < prefix.length) {
      return false;
    }
    for (int i = 0; i < prefix.length; i++) {
      if (content[i] != prefix[i]) {
        return false;
      }
    }
    return true;
  }
}
