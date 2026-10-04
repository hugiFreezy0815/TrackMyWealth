-- =============================================================================================
-- V66: PDF import templates (#267, #268)
-- =============================================================================================
-- Product owner decision 2026-10-04 (spike #238): the import also reads PDF statements. A
-- template's file_format says how its file is read: CSV (delimited text, the default for every
-- existing row), PDF_TEXT (the PDF's text layer) or PDF_OCR (a scanned PDF, through local OCR).
-- pdf_layout is the line-based extraction a PDF template needs (columns, row pattern, document
-- marker, record-start pattern; see ImportPdfLayout); a CSV template has none. #268 extends the
-- layout with further optional fields, never by changing these.
-- =============================================================================================

ALTER TABLE import_template
ADD COLUMN file_format TEXT NOT NULL DEFAULT 'CSV';

ALTER TABLE import_template
ADD CONSTRAINT chk_import_template_file_format CHECK (
    file_format IN ('CSV', 'PDF_TEXT', 'PDF_OCR')
);

ALTER TABLE import_template
ADD COLUMN pdf_layout JSONB;

-- A PDF template cannot be read without its layout, and a CSV template must not carry one.
ALTER TABLE import_template
ADD CONSTRAINT chk_import_template_pdf_layout CHECK (
    (file_format = 'CSV') = (pdf_layout IS NULL)
);
