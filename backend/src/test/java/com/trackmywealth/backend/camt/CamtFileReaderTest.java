package com.trackmywealth.backend.camt;

import static com.trackmywealth.backend.testsupport.CamtFixtures.IBAN;
import static com.trackmywealth.backend.testsupport.CamtFixtures.camt052;
import static com.trackmywealth.backend.testsupport.CamtFixtures.camt053;
import static com.trackmywealth.backend.testsupport.CamtFixtures.entry;
import static com.trackmywealth.backend.testsupport.CamtFixtures.zip;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

/** #350: a CAMT file told by its bytes, and ZIP archives of statements within their limits. */
class CamtFileReaderTest {

  private static final byte[] STATEMENT =
      camt053("001.08").statement(IBAN, s -> s.entry(entry("1.00", "CRDT", "2031-01-02"))).bytes();
  private static final byte[] REPORT =
      camt052("001.08").statement(IBAN, s -> s.entry(entry("2.00", "DBIT", "2031-01-03"))).bytes();

  @Test
  void tellsXmlAndZipArchivesByTheirBytesAlone() {
    assertThat(CamtFileReader.isCandidate(STATEMENT)).isTrue();
    assertThat(CamtFileReader.isCandidate(zip(STATEMENT))).isTrue();
    assertThat(CamtFileReader.isCandidate(utf8(" \t\r\n<Document/>"))).isTrue();
    assertThat(
            CamtFileReader.isCandidate(
                bytes(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}, utf8("<a/>"))))
        .isTrue();
    assertThat(
            CamtFileReader.isCandidate(
                bytes(
                    new byte[] {(byte) 0xFE, (byte) 0xFF},
                    "<a/>".getBytes(StandardCharsets.UTF_16BE))))
        .isTrue();
    assertThat(
            CamtFileReader.isCandidate(
                bytes(
                    new byte[] {(byte) 0xFF, (byte) 0xFE},
                    "<a/>".getBytes(StandardCharsets.UTF_16LE))))
        .isTrue();
    assertThat(CamtFileReader.isCandidate(utf8("Datum;Betrag\n01.01.2031;1,00\n"))).isFalse();
    assertThat(CamtFileReader.isCandidate(utf8("%PDF-1.7"))).isFalse();
    assertThat(CamtFileReader.isCandidate(new byte[0])).isFalse();
  }

  @Test
  void readsAUtf16Statement() throws Exception {
    byte[] utf16 =
        bytes(
            new byte[] {(byte) 0xFE, (byte) 0xFF},
            new String(STATEMENT, StandardCharsets.UTF_8)
                .replace("encoding=\"UTF-8\"", "encoding=\"UTF-16\"")
                .getBytes(StandardCharsets.UTF_16BE));

    assertThat(CamtFileReader.read(utf16))
        .singleElement()
        .satisfies(m -> assertThat(m.isStatement()).isTrue());
  }

  @Test
  void readsEveryStatementOfAZipArchiveSkippingDirectoriesAndNeverUsingNames() throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (ZipOutputStream archive = new ZipOutputStream(out, StandardCharsets.ISO_8859_1)) {
      archive.putNextEntry(new ZipEntry("../../etc/"));
      archive.closeEntry();
      // A name that is no UTF-8 and climbs out of any directory: neither matters.
      archive.putNextEntry(new ZipEntry("../../Ausz\u00fcge/../evil.xml"));
      archive.write(STATEMENT);
      archive.closeEntry();
      archive.putNextEntry(new ZipEntry("C:\\report.xml"));
      archive.write(REPORT);
      archive.closeEntry();
    }

    assertThat(CamtFileReader.read(out.toByteArray()))
        .extracting(CamtMessage::messageType)
        .containsExactly(CamtXmlReader.STATEMENT, CamtXmlReader.REPORT);
  }

  @Test
  void refusesAZipArchiveEntryThatIsNotXmlANestedArchiveAndAnArchiveWithoutStatements()
      throws IOException {
    assertThatThrownBy(() -> CamtFileReader.read(zip(STATEMENT, utf8("Datum;Betrag\n"))))
        .isInstanceOf(CamtFormatException.class)
        .hasMessageContaining("not XML");
    assertThatThrownBy(() -> CamtFileReader.read(zip(zip(STATEMENT))))
        .isInstanceOf(CamtFormatException.class)
        .hasMessageContaining("not XML");
    assertThatThrownBy(() -> CamtFileReader.read(zip()))
        .isInstanceOf(CamtFormatException.class)
        .hasMessageContaining("no XML statement");
    assertThatThrownBy(() -> CamtFileReader.read(zip(utf8("<a/>"))))
        .isInstanceOf(CamtUnsupportedException.class);
  }

  @Test
  void refusesADamagedArchive() {
    byte[] archive = zip(STATEMENT);
    byte[] truncated = Arrays.copyOf(archive, archive.length / 2);

    assertThatThrownBy(() -> CamtFileReader.read(truncated))
        .isInstanceOf(CamtFormatException.class);
  }

  // A ZIP bomb stops at the entry limit, while it inflates.
  @Test
  void refusesAnEntryThatInflatesBeyondTheLimit() {
    byte[] bomb = new byte[CamtFileReader.MAX_ENTRY_BYTES + 1];
    Arrays.fill(bomb, (byte) ' ');
    bomb[0] = '<';

    assertThatThrownBy(() -> CamtFileReader.read(zip(bomb))).isInstanceOf(CamtLimitException.class);
  }

  @Test
  void refusesAnEntryCompressedMoreThanTheRatioAllows() {
    // About 1 MB of blanks compresses some thousandfold: no statement does.
    byte[] dense = new byte[1024 * 1024];
    Arrays.fill(dense, (byte) ' ');
    dense[0] = '<';

    assertThatThrownBy(() -> CamtFileReader.read(zip(dense)))
        .isInstanceOf(CamtLimitException.class)
        .hasMessageContaining("inflates");
  }

  // Each entry within its own limits, but all of them together more than one import may hold.
  @Test
  void refusesArchivesThatInflateBeyondTheTotalLimit() {
    byte[] large = statementWithComment(4 * 1024 * 1024);

    assertThatThrownBy(() -> CamtFileReader.read(zip(large, large, large, large, large, large)))
        .isInstanceOf(CamtLimitException.class)
        .hasMessageContaining("inflates");
  }

  @Test
  void refusesMoreArchiveEntriesThanTheLimit() {
    byte[][] files = new byte[CamtFileReader.MAX_ARCHIVE_ENTRIES + 1][];
    Arrays.fill(files, STATEMENT);

    assertThatThrownBy(() -> CamtFileReader.read(zip(files)))
        .isInstanceOf(CamtLimitException.class)
        .hasMessageContaining("entries");
  }

  @Test
  void refusesAFileLargerThanTheUploadLimitAndAFileThatIsNeitherXmlNorZip() {
    assertThatThrownBy(() -> CamtFileReader.read(new byte[CamtFileReader.MAX_FILE_BYTES + 1]))
        .isInstanceOf(CamtLimitException.class);
    assertThatThrownBy(() -> CamtFileReader.read(utf8("Datum;Betrag")))
        .isInstanceOf(CamtFormatException.class);
  }

  // A valid statement padded by a comment that compresses about tenfold: random digits within text.
  private static byte[] statementWithComment(int size) {
    Random random = new Random(350);
    StringBuilder comment = new StringBuilder(size);
    while (comment.length() < size) {
      comment.append("synthetic padding ").append(random.nextInt(1_000_000)).append(' ');
    }
    String xml = new String(STATEMENT, StandardCharsets.UTF_8);
    int afterProlog = xml.indexOf("?>") + 2;
    return (xml.substring(0, afterProlog) + "<!--" + comment + "-->" + xml.substring(afterProlog))
        .getBytes(StandardCharsets.UTF_8);
  }

  private static byte[] utf8(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }

  private static byte[] bytes(byte[] first, byte[] second) {
    byte[] joined = Arrays.copyOf(first, first.length + second.length);
    System.arraycopy(second, 0, joined, first.length, second.length);
    return joined;
  }
}
