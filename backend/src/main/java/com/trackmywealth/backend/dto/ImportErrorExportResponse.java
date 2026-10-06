package com.trackmywealth.backend.dto;

/**
 * FR-IMP-012: a batch's {@code ERROR} rows as CSV text - their raw cells, then the error code and
 * its message - to be written in {@code encoding}, the source file's own.
 */
public record ImportErrorExportResponse(String csv, String encoding, String fileName) {}
