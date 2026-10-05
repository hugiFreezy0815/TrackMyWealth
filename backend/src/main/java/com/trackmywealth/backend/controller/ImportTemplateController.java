package com.trackmywealth.backend.controller;

import com.trackmywealth.backend.dto.ImportTemplateCandidateResponse;
import com.trackmywealth.backend.dto.ImportTemplateRequest;
import com.trackmywealth.backend.dto.ImportTemplateResponse;
import com.trackmywealth.backend.dto.ImportTemplateTestResponse;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import com.trackmywealth.backend.service.ImportTemplateService;
import com.trackmywealth.backend.web.IfMatchVersionParser;
import com.trackmywealth.backend.web.RetryableWhenBusy;
import com.trackmywealth.backend.web.VersionedResponse;
import jakarta.validation.Valid;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * US-07-03, #268: CSV and PDF import templates - define, version, detect and dry-run. Rules in
 * {@link ImportTemplateService}; files are parsed in memory and never stored here (the upload
 * itself is US-07-04).
 */
@RestController
@RequestMapping("/api/v1/import-templates")
public class ImportTemplateController {

  private static final String BY_ID = "/{id}";

  private final ImportTemplateService importTemplateService;

  public ImportTemplateController(ImportTemplateService importTemplateService) {
    this.importTemplateService = importTemplateService;
  }

  /** Current versions of the shipped and the workspace's own templates, by name. */
  @GetMapping
  public List<ImportTemplateResponse> list(
      @RequestParam(required = false) UUID institutionCatalogueId,
      @RequestParam(defaultValue = "false") boolean includeInactive,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    return importTemplateService.list(institutionCatalogueId, includeInactive, actor);
  }

  /** Any version, current or retired. */
  @GetMapping(BY_ID)
  public ResponseEntity<ImportTemplateResponse> get(
      @PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    ImportTemplateResponse template = importTemplateService.get(id, actor);
    return VersionedResponse.ok(template, template.version());
  }

  @PostMapping
  public ResponseEntity<ImportTemplateResponse> create(
      @Valid @RequestBody ImportTemplateRequest request,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    ImportTemplateResponse created = importTemplateService.create(request, actor);
    URI location =
        ServletUriComponentsBuilder.fromCurrentRequest()
            .path(BY_ID)
            .buildAndExpand(created.id())
            .toUri();
    return ResponseEntity.created(location)
        .eTag(IfMatchVersionParser.toEtag(created.version()))
        .body(created);
  }

  /**
   * Replaces the template. A changed parse-relevant field returns a new version with a new {@code
   * id} (FR-IMP-023); a new name or institution only returns the same one.
   */
  @PutMapping(BY_ID)
  public ResponseEntity<ImportTemplateResponse> update(
      @PathVariable UUID id,
      @Valid @RequestBody ImportTemplateRequest request,
      @RequestHeader(value = IfMatchVersionParser.HEADER, required = false) String ifMatch,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    ImportTemplateResponse updated =
        importTemplateService.update(id, request, IfMatchVersionParser.parse(ifMatch), actor);
    return VersionedResponse.ok(updated, updated.version());
  }

  /** Hides the template from detection and the default list; it stays usable for history. */
  @PostMapping(BY_ID + "/deactivate")
  public ResponseEntity<ImportTemplateResponse> deactivate(
      @PathVariable UUID id,
      @RequestHeader(value = IfMatchVersionParser.HEADER, required = false) String ifMatch,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    ImportTemplateResponse template =
        importTemplateService.setActive(id, false, IfMatchVersionParser.parse(ifMatch), actor);
    return VersionedResponse.ok(template, template.version());
  }

  @PostMapping(BY_ID + "/activate")
  public ResponseEntity<ImportTemplateResponse> activate(
      @PathVariable UUID id,
      @RequestHeader(value = IfMatchVersionParser.HEADER, required = false) String ifMatch,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    ImportTemplateResponse template =
        importTemplateService.setActive(id, true, IfMatchVersionParser.parse(ifMatch), actor);
    return VersionedResponse.ok(template, template.version());
  }

  /** 204 with every version, only while no import used it; otherwise 409, deactivate instead. */
  @DeleteMapping(BY_ID)
  public ResponseEntity<Void> delete(
      @PathVariable UUID id,
      @RequestHeader(value = IfMatchVersionParser.HEADER, required = false) String ifMatch,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    importTemplateService.delete(id, IfMatchVersionParser.parse(ifMatch), actor);
    return ResponseEntity.noContent().build();
  }

  /**
   * FR-IMP-022: the templates that can read the file, best first. Writes nothing. A 422 {@code
   * IMPORT_PDF_NO_TEXT} for a scanned PDF (only an OCR template, chosen explicitly, reads one), a
   * 503 {@code IMPORT_PDF_BUSY} (with {@code Retry-After}) when the server is reading too many PDFs
   * at once.
   */
  @RetryableWhenBusy
  @PostMapping(path = "/detect", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public List<ImportTemplateCandidateResponse> detect(
      @RequestPart("file") MultipartFile file,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    return importTemplateService.detect(bytesOf(file), actor);
  }

  /**
   * Dry run of a saved template version: the first rows parsed, plus counts. Writes nothing. A 503
   * {@code IMPORT_PDF_BUSY} or {@code IMPORT_OCR_UNAVAILABLE} when the server cannot read the PDF
   * now; {@code Retry-After} when waiting helps.
   */
  @RetryableWhenBusy
  @PostMapping(path = BY_ID + "/test", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ImportTemplateTestResponse testSaved(
      @PathVariable UUID id,
      @RequestPart("file") MultipartFile file,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    return importTemplateService.test(id, bytesOf(file), actor);
  }

  /**
   * Dry run of an unsaved template (the mapping screen's live preview); its {@code name} and {@code
   * headerColumns} are not needed. Writes nothing. A 503 as for {@link #testSaved}.
   */
  @RetryableWhenBusy
  @PostMapping(path = "/test", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ImportTemplateTestResponse testUnsaved(
      @RequestPart("file") MultipartFile file,
      @RequestPart("template") ImportTemplateRequest template,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    return importTemplateService.test(template, bytesOf(file), actor);
  }

  private static byte[] bytesOf(MultipartFile file) {
    try {
      return file.getBytes();
    } catch (IOException e) {
      throw new UncheckedIOException("Could not read the uploaded file.", e);
    }
  }
}
