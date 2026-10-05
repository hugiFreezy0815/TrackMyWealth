package com.trackmywealth.backend.service;

import com.trackmywealth.backend.dto.ImportPdfLayout;
import java.util.List;

/** A copy of a PDF layout with some of its fields changed, for tests (#268). */
final class PdfLayoutBuilder {

  private List<String> columns;
  private String rowPattern;
  private String documentMarker;
  private String recordStartPattern;
  private List<String> headerLabels;
  private String continuationColumn;
  private String sectionPattern;
  private String sectionColumn;
  private String balanceLinePattern;
  private String balanceColumn;
  private String continuationEndPattern;

  private PdfLayoutBuilder() {}

  static PdfLayoutBuilder from(ImportPdfLayout layout) {
    PdfLayoutBuilder builder = new PdfLayoutBuilder();
    builder.columns = layout.columns();
    builder.rowPattern = layout.rowPattern();
    builder.documentMarker = layout.documentMarker();
    builder.recordStartPattern = layout.recordStartPattern();
    builder.headerLabels = layout.headerLabels();
    builder.continuationColumn = layout.continuationColumn();
    builder.sectionPattern = layout.sectionPattern();
    builder.sectionColumn = layout.sectionColumn();
    builder.balanceLinePattern = layout.balanceLinePattern();
    builder.balanceColumn = layout.balanceColumn();
    builder.continuationEndPattern = layout.continuationEndPattern();
    return builder;
  }

  /** A layout of the four fields every PDF layout has, and none of the optional ones. */
  static PdfLayoutBuilder of(
      List<String> columns, String rowPattern, String documentMarker, String recordStartPattern) {
    return from(new ImportPdfLayout(columns, rowPattern, documentMarker, recordStartPattern));
  }

  PdfLayoutBuilder headerLabels(List<String> value) {
    headerLabels = value;
    return this;
  }

  PdfLayoutBuilder continuationColumn(String value) {
    continuationColumn = value;
    return this;
  }

  PdfLayoutBuilder sectionPattern(String value) {
    sectionPattern = value;
    return this;
  }

  PdfLayoutBuilder sectionColumn(String value) {
    sectionColumn = value;
    return this;
  }

  PdfLayoutBuilder balanceLinePattern(String value) {
    balanceLinePattern = value;
    return this;
  }

  PdfLayoutBuilder balanceColumn(String value) {
    balanceColumn = value;
    return this;
  }

  PdfLayoutBuilder continuationEndPattern(String value) {
    continuationEndPattern = value;
    return this;
  }

  ImportPdfLayout build() {
    return new ImportPdfLayout(
        columns,
        rowPattern,
        documentMarker,
        recordStartPattern,
        headerLabels,
        continuationColumn,
        sectionPattern,
        sectionColumn,
        balanceLinePattern,
        balanceColumn,
        continuationEndPattern);
  }
}
