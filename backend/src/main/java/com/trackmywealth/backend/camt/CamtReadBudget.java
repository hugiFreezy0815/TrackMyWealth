package com.trackmywealth.backend.camt;

/**
 * What one import may read across all XML documents of its file (one, or each entry of a ZIP
 * archive): elements and entries. Counted as they are read, so a limit stops the read at once.
 */
final class CamtReadBudget {

  // A 5 MB statement holds well under 200,000 elements; a ZIP may hold several such statements.
  static final int MAX_ELEMENTS = 1_000_000;
  // As many as an import file may hold rows (ImportFileParserService.MAX_DATA_ROWS).
  static final int MAX_ENTRIES = 20_000;

  private int elements;
  private int entries;

  void countElement() throws CamtLimitException {
    elements++;
    if (elements > MAX_ELEMENTS) {
      throw new CamtLimitException(
          "The file holds more than " + MAX_ELEMENTS + " XML elements, more than one import may.");
    }
  }

  void countEntry() throws CamtLimitException {
    entries++;
    if (entries > MAX_ENTRIES) {
      throw new CamtLimitException(
          "The file holds more than " + MAX_ENTRIES + " entries, more than one import may.");
    }
  }
}
