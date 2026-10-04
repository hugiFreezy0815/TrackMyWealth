package com.trackmywealth.backend.service;

import com.trackmywealth.backend.dto.ImportColumnMapping;
import com.trackmywealth.backend.dto.ImportParseResult;
import com.trackmywealth.backend.dto.ImportPdfLayout;
import com.trackmywealth.backend.dto.ImportPreviewRowResponse;
import com.trackmywealth.backend.dto.ImportRowErrorResponse;
import com.trackmywealth.backend.dto.ImportTemplateCandidateResponse;
import com.trackmywealth.backend.dto.ImportTemplateDefinition;
import com.trackmywealth.backend.dto.ImportTemplateRequest;
import com.trackmywealth.backend.dto.ImportTemplateResponse;
import com.trackmywealth.backend.dto.ImportTemplateTestResponse;
import com.trackmywealth.backend.dto.ImportTemplateValues;
import com.trackmywealth.backend.dto.ParsedImportRow;
import com.trackmywealth.backend.dto.ResolvedImportTemplate;
import com.trackmywealth.backend.entity.ImportTemplate;
import com.trackmywealth.backend.error.ApiErrorCode;
import com.trackmywealth.backend.error.ApiException;
import com.trackmywealth.backend.repository.ImportTemplateRepository;
import com.trackmywealth.backend.repository.InstitutionCatalogueRepository;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.ObjectMapper;

/**
 * US-07-03, FR-IMP-009/020..024, FR-INS-010: a workspace's CSV import templates - define one,
 * version it, test it against a sample file and have it detected for the next upload.
 *
 * <p>Rules this service owns:
 *
 * <ul>
 *   <li>Visibility: the shipped templates ({@code workspace_id IS NULL}) plus the workspace's own,
 *       as V65's RLS allows. Any active member may create and change the workspace's templates;
 *       shipped ones are read-only ({@code 403 IMPORT_TEMPLATE_READ_ONLY}). An id the caller cannot
 *       see is the audited, non-enumerating 404 (US-28-02/03).
 *   <li>Versioning (FR-IMP-023): a change to any field of {@link ImportTemplateDefinition} or to
 *       the header columns writes a new version (same family, {@code templateVersion} + 1,
 *       effective today) and retires the old one in the same transaction; the old row itself never
 *       changes again, so a batch that used it stays reproducible. A change of name or institution
 *       only is made in place. Only the current version can be changed.
 *   <li>Every change requires the version the client last read through {@code If-Match} (ADR 0004);
 *       the row is locked first, so concurrent changes of one template run one after the other.
 *   <li>Delete (FR-LIF-001): every version at once, and only while no import batch used any of
 *       them; otherwise {@code 409 IMPORT_TEMPLATE_IN_USE} - deactivate instead, which hides the
 *       template from detection and from the default list.
 * </ul>
 *
 * <p>Detection and dry runs parse the uploaded file in memory only: it is never stored, and only
 * its size and counts are logged, never a cell.
 */
@Service
public class ImportTemplateService {

  // Names the resource in a 412 VERSION_CONFLICT detail (VersionPreconditionService).
  private static final String VERSIONED_RESOURCE = "import template";
  private static final String ENTITY_TYPE = "ImportTemplate";
  private static final String MESSAGE_PREFIX = "tmw.import.row.";
  private static final Logger LOG = LoggerFactory.getLogger(ImportTemplateService.class);

  private final ImportTemplateRepository templateRepository;
  private final InstitutionCatalogueRepository institutionCatalogueRepository;
  private final ImportFileParserService parser;
  private final PdfImportReaderService pdfReader;
  // Detection and dry runs read the templates in a short transaction of their own and parse the
  // file after it: an OCR run can take minutes and must not hold a pooled connection meanwhile.
  private final TransactionTemplate readOnlyTransaction;
  private final AccessControlService accessControlService;
  private final VersionPreconditionService versionPreconditionService;
  private final BusinessDateService businessDateService;
  private final MessageSource messageSource;
  private final ObjectMapper objectMapper;
  // Not TypeReference: its anonymous subclass would be a class in this package (ArchUnit naming).
  private final JavaType stringMap;
  private final JavaType stringList;

  public ImportTemplateService(
      ImportTemplateRepository templateRepository,
      InstitutionCatalogueRepository institutionCatalogueRepository,
      ImportFileParserService parser,
      PdfImportReaderService pdfReader,
      PlatformTransactionManager transactionManager,
      AccessControlService accessControlService,
      VersionPreconditionService versionPreconditionService,
      BusinessDateService businessDateService,
      MessageSource messageSource,
      ObjectMapper objectMapper) {
    this.templateRepository = templateRepository;
    this.institutionCatalogueRepository = institutionCatalogueRepository;
    this.parser = parser;
    this.pdfReader = pdfReader;
    this.readOnlyTransaction = new TransactionTemplate(transactionManager);
    this.readOnlyTransaction.setReadOnly(true);
    this.accessControlService = accessControlService;
    this.versionPreconditionService = versionPreconditionService;
    this.businessDateService = businessDateService;
    this.messageSource = messageSource;
    this.objectMapper = objectMapper;
    this.stringMap =
        objectMapper
            .getTypeFactory()
            .constructMapType(LinkedHashMap.class, String.class, String.class);
    this.stringList =
        objectMapper.getTypeFactory().constructCollectionType(List.class, String.class);
  }

  /** The current versions, by name; inactive ones only with {@code includeInactive}. */
  @Transactional(readOnly = true)
  public List<ImportTemplateResponse> list(
      UUID institutionCatalogueId, boolean includeInactive, AuthenticatedUserPrincipal actor) {
    accessControlService.requireActingMember(actor);
    return templateRepository.findCurrentVisibleTo(actor.workspaceId()).stream()
        .filter(t -> includeInactive || t.isActive())
        .filter(
            t ->
                institutionCatalogueId == null
                    || institutionCatalogueId.equals(t.getInstitutionCatalogueId()))
        .map(this::toResponse)
        .toList();
  }

  /** Any version, current or not - a batch's history refers to old ones. */
  @Transactional(readOnly = true)
  public ImportTemplateResponse get(UUID id, AuthenticatedUserPrincipal actor) {
    accessControlService.requireActingMember(actor);
    return toResponse(requireVisible(id, actor));
  }

  /**
   * For US-07-04: the template version an upload uses, if the caller may see it, it is current and
   * active, and this release can import with it.
   */
  @Transactional(readOnly = true)
  public ResolvedImportTemplate resolveForImport(UUID id, AuthenticatedUserPrincipal actor) {
    accessControlService.requireActingMember(actor);
    ImportTemplate template = requireVisible(id, actor);
    if (!template.isCurrent() || !template.isActive()) {
      throw new ApiException(
          HttpStatus.UNPROCESSABLE_CONTENT,
          ApiErrorCode.UNPROCESSABLE,
          "Only the current version of an active template can be used for a new import.");
    }
    ImportTemplateDefinition definition = definitionOf(template);
    parser.validateTemplate(definition, null);
    return new ResolvedImportTemplate(template.getId(), template.getTemplateVersion(), definition);
  }

  @Transactional
  public ImportTemplateResponse create(
      ImportTemplateRequest request, AuthenticatedUserPrincipal actor) {
    accessControlService.requireActingMember(actor);
    ImportTemplateDefinition definition = definitionOf(request);
    List<String> headerColumns = validated(definition, request.headerColumns());
    requireKnownInstitution(request.institutionCatalogueId());

    ImportTemplate template = new ImportTemplate();
    template.setTemplateFamilyId(UUID.randomUUID());
    template.setWorkspaceId(actor.workspaceId());
    template.setTemplateVersion("1");
    apply(template, request, definition, headerColumns);
    return toResponse(templateRepository.saveAndFlush(template));
  }

  /**
   * Replaces the template. A change of any parse-relevant field returns a new version with a new
   * {@code id}; a change of name or institution only returns the same row.
   */
  @Transactional
  public ImportTemplateResponse update(
      UUID id,
      ImportTemplateRequest request,
      Integer expectedVersion,
      AuthenticatedUserPrincipal actor) {
    ImportTemplate current = requireChangeable(id, expectedVersion, actor);
    ImportTemplateDefinition definition = definitionOf(request);
    List<String> headerColumns =
        validated(
            definition,
            request.headerColumns() == null ? readHeaderColumns(current) : request.headerColumns());
    requireKnownInstitution(request.institutionCatalogueId());

    boolean parseRelevantChange =
        !definition.equals(definitionOf(current))
            || !Objects.equals(headerColumns, readHeaderColumns(current));
    if (!parseRelevantChange) {
      current.setName(request.name().strip());
      current.setInstitutionCatalogueId(request.institutionCatalogueId());
      return toResponse(templateRepository.saveAndFlush(current));
    }

    // Retire the old version first: V64's partial unique index allows one current version only.
    current.setCurrent(false);
    templateRepository.saveAndFlush(current);
    ImportTemplate next = new ImportTemplate();
    next.setTemplateFamilyId(current.getTemplateFamilyId());
    next.setWorkspaceId(current.getWorkspaceId());
    next.setTemplateVersion(String.valueOf(Integer.parseInt(current.getTemplateVersion()) + 1));
    next.setActive(current.isActive());
    apply(next, request, definition, headerColumns);
    return toResponse(templateRepository.saveAndFlush(next));
  }

  /** Hides the template from detection and the default list. Idempotent. */
  @Transactional
  public ImportTemplateResponse setActive(
      UUID id, boolean active, Integer expectedVersion, AuthenticatedUserPrincipal actor) {
    ImportTemplate template = requireChangeable(id, expectedVersion, actor);
    if (template.isActive() != active) {
      template.setActive(active);
      template = templateRepository.saveAndFlush(template);
    }
    return toResponse(template);
  }

  /** FR-LIF-001: every version, only while no import batch used any of them. */
  @Transactional
  public void delete(UUID id, Integer expectedVersion, AuthenticatedUserPrincipal actor) {
    ImportTemplate template = requireChangeable(id, expectedVersion, actor);
    if (templateRepository.isFamilyUsed(template.getTemplateFamilyId())) {
      throw new ApiException(
          HttpStatus.CONFLICT,
          ApiErrorCode.IMPORT_TEMPLATE_IN_USE,
          "An import used this template, so it is kept for that import's history. Deactivate it"
              + " instead.");
    }
    templateRepository.deleteFamily(template.getTemplateFamilyId());
  }

  /**
   * FR-IMP-022: the active templates that can read {@code content}, best first - an exact header
   * fingerprint before a header that merely holds every mapped column, the workspace's own before
   * shipped ones, then by name. A template whose settings cannot read the file is left out. A PDF
   * is read once, however many PDF templates there are, and a PDF template is never an exact header
   * match: its columns are named by its layout, not read from the file.
   */
  public List<ImportTemplateCandidateResponse> detect(
      byte[] content, AuthenticatedUserPrincipal actor) {
    List<ImportTemplate> templates =
        readOnlyTransaction.execute(
            status -> {
              accessControlService.requireActingMember(actor);
              return templateRepository.findCurrentVisibleTo(actor.workspaceId());
            });
    List<ImportTemplateCandidateResponse> candidates = new ArrayList<>();
    String pdfText = readPdfTextForDetection(templates, content);
    for (ImportTemplate template : templates) {
      String match = match(template, content, pdfText);
      if (match != null) {
        candidates.add(new ImportTemplateCandidateResponse(toResponse(template), match));
      }
    }
    candidates.sort(
        Comparator.comparing(
                (ImportTemplateCandidateResponse c) ->
                    !ImportTemplateCandidateResponse.EXACT_HEADER.equals(c.match()))
            .thenComparing(c -> c.template().systemProvided())
            .thenComparing(c -> c.template().name()));
    if (LOG.isInfoEnabled()) {
      LOG.info(
          "Detected import templates for a {} byte file: {} candidates",
          content.length,
          candidates.size());
    }
    return candidates;
  }

  /** Dry run of a saved template version on {@code content}; nothing is stored. */
  public ImportTemplateTestResponse test(
      UUID id, byte[] content, AuthenticatedUserPrincipal actor) {
    ImportTemplateDefinition definition =
        readOnlyTransaction.execute(
            status -> {
              accessControlService.requireActingMember(actor);
              return definitionOf(requireVisible(id, actor));
            });
    return preview(parser.parse(content, definition, null));
  }

  /** Dry run of a template that is not saved yet (the mapping screen's live preview). */
  public ImportTemplateTestResponse test(
      ImportTemplateRequest template, byte[] content, AuthenticatedUserPrincipal actor) {
    accessControlService.requireActingMember(actor);
    return preview(parser.parse(content, definitionOf(template), null));
  }

  // --- rules ---------------------------------------------------------------------------------

  private ImportTemplate requireVisible(UUID id, AuthenticatedUserPrincipal actor) {
    return templateRepository
        .findVisibleTo(id, actor.workspaceId())
        .orElseThrow(() -> accessControlService.denyAsNotFound(actor, ENTITY_TYPE, id));
  }

  // Authorization before the version (ADR 0004): hidden, then read-only, then stale, then retired.
  // The lock comes first, so the row checked below is the locked, current one. It reaches the
  // workspace's own rows only (V65's RLS lets a workspace lock (SELECT ... FOR UPDATE) nothing
  // else), so only when it misses does a plain read tell a shipped template from a hidden one.
  private ImportTemplate requireChangeable(
      UUID id, Integer expectedVersion, AuthenticatedUserPrincipal actor) {
    accessControlService.requireActingMember(actor);
    Optional<ImportTemplate> own = templateRepository.findOwnForUpdate(id, actor.workspaceId());
    if (own.isEmpty()) {
      // requireVisible throws the audited 404 for a template the workspace cannot see.
      if (requireVisible(id, actor).isShared()) {
        throw new ApiException(
            HttpStatus.FORBIDDEN,
            ApiErrorCode.IMPORT_TEMPLATE_READ_ONLY,
            "Shipped import templates are shared by every workspace and cannot be changed. Create a"
                + " template of your own instead.");
      }
      // Visible and not shipped means own, which the lock would have found: only a template written
      // concurrently with this id gets here, and it stays hidden.
      throw accessControlService.denyAsNotFound(actor, ENTITY_TYPE, id);
    }
    ImportTemplate template = own.get();
    versionPreconditionService.requireCurrent(
        expectedVersion, template.getVersion(), VERSIONED_RESOURCE);
    if (!template.isCurrent()) {
      ApiException superseded =
          new ApiException(
              HttpStatus.PRECONDITION_FAILED,
              ApiErrorCode.VERSION_CONFLICT,
              "A newer version of this import template replaced this one. Change the current"
                  + " version instead.");
      templateRepository
          .findByTemplateFamilyIdAndCurrentTrue(template.getTemplateFamilyId())
          .ifPresent(c -> superseded.getBody().setProperty("currentId", c.getId()));
      throw superseded;
    }
    return template;
  }

  // The header columns the template keeps, after the parser's rules and the header rules. A PDF
  // template's are its layout's column names (#267), whatever the request sent.
  private List<String> validated(
      ImportTemplateDefinition definition, List<String> requestedHeaderColumns) {
    if (definition.isPdf()) {
      parser.validateTemplate(definition, null);
      return definition.pdfLayout().columns();
    }
    List<String> headerColumns =
        requestedHeaderColumns == null || requestedHeaderColumns.isEmpty()
            ? null
            : List.copyOf(requestedHeaderColumns);
    if (definition.hasHeaderRow() && headerColumns == null) {
      throw invalid(
          "headerColumns",
          "Send the sample file's header columns (as the dry run returned them) so the template"
              + " can be detected.");
    }
    if (!definition.hasHeaderRow() && headerColumns != null) {
      throw invalid("headerColumns", "A template for a file without a header row has none.");
    }
    if (headerColumns != null && headerColumns.stream().anyMatch(Objects::isNull)) {
      throw invalid("headerColumns", "A header column must not be null.");
    }
    parser.validateTemplate(definition, headerColumns);
    return headerColumns;
  }

  private void requireKnownInstitution(UUID institutionCatalogueId) {
    if (institutionCatalogueId != null
        && !institutionCatalogueRepository.existsById(institutionCatalogueId)) {
      throw invalid("institutionCatalogueId", "No such institution in the catalogue.");
    }
  }

  private static ApiException invalid(String field, String detail) {
    ApiException exception =
        new ApiException(
            HttpStatus.UNPROCESSABLE_CONTENT, ApiErrorCode.IMPORT_TEMPLATE_INVALID, detail);
    exception.getBody().setProperty("field", field);
    return exception;
  }

  // The text layer of a PDF upload, read once for every PDF_TEXT template to try; null when there
  // is none to try, the file is no PDF, or it cannot be read as one.
  private String readPdfTextForDetection(List<ImportTemplate> templates, byte[] content) {
    boolean anyTextTemplate =
        templates.stream()
            .anyMatch(t -> ImportTemplateValues.FORMAT_PDF_TEXT.equals(t.getFileFormat()));
    if (!anyTextTemplate || !PdfImportReaderService.isPdf(content)) {
      return null;
    }
    try {
      return pdfReader.readText(content, false);
    } catch (ApiException e) {
      return null;
    }
  }

  // Whether this release can apply the template at all (e.g. not a retired setting).
  private boolean isApplicable(ImportTemplateDefinition definition) {
    try {
      parser.validateTemplate(definition, null);
      return true;
    } catch (ApiException e) {
      return false;
    }
  }

  private String match(ImportTemplate template, byte[] content, String pdfText) {
    ImportTemplateDefinition definition = definitionOf(template);
    // An OCR template is never a candidate: recognizing every page once per template is too slow
    // for detection. The member picks it explicitly.
    if (!template.isActive() || !definition.hasHeaderRow() || definition.isOcr()) {
      return null;
    }
    if (definition.isPdf()) {
      return pdfText != null
              && isApplicable(definition)
              && PdfImportReaderService.isLayoutOf(pdfText, definition.pdfLayout())
          ? ImportTemplateCandidateResponse.MAPPED_COLUMNS_PRESENT
          : null;
    }
    List<String> header;
    try {
      parser.validateTemplate(definition, null);
      header = parser.readHeader(content, definition);
    } catch (ApiException e) {
      // This template cannot read the file (or this release cannot apply it): no candidate.
      return null;
    }
    if (ImportFileParserService.fingerprint(header).equals(template.getHeaderFingerprint())) {
      return ImportTemplateCandidateResponse.EXACT_HEADER;
    }
    return parser.missingColumns(definition.columnMapping(), header).isEmpty()
        ? ImportTemplateCandidateResponse.MAPPED_COLUMNS_PRESENT
        : null;
  }

  // --- mapping -------------------------------------------------------------------------------

  // The request's definition, its encoding in the canonical spelling ("utf-8" is UTF-8).
  private static ImportTemplateDefinition definitionOf(ImportTemplateRequest request) {
    ImportTemplateDefinition definition = request.toDefinition();
    String encoding =
        ImportTemplateValues.ENCODINGS.stream()
            .filter(known -> known.equalsIgnoreCase(definition.encoding()))
            .findFirst()
            .orElse(definition.encoding());
    String fixedCurrency =
        definition.fixedCurrency() == null
            ? null
            : definition.fixedCurrency().toUpperCase(Locale.ROOT);
    return new ImportTemplateDefinition(
        definition.templateClass(),
        definition.delimiter(),
        encoding,
        definition.decimalSeparator(),
        definition.thousandsSeparator(),
        definition.dateFormat(),
        definition.headerRowIndex(),
        definition.preambleRowCount(),
        definition.trailingSummaryRowCount(),
        definition.amountRepresentation(),
        definition.currencyMode(),
        fixedCurrency,
        definition.columnMapping(),
        definition.typeMapping(),
        definition.accountIdentificationStrategy(),
        definition.fileFormat(),
        definition.pdfLayout());
  }

  private ImportTemplateDefinition definitionOf(ImportTemplate template) {
    return new ImportTemplateDefinition(
        template.getTemplateClass(),
        template.getDelimiter(),
        template.getEncoding(),
        template.getDecimalSeparator(),
        template.getThousandsSeparator(),
        template.getDateFormat(),
        template.getHeaderRowIndex(),
        template.getPreambleRowCount(),
        template.getTrailingSummaryRowCount(),
        template.getAmountRepresentation(),
        template.getCurrencyMode(),
        template.getFixedCurrency(),
        objectMapper.readValue(template.getColumnMapping(), ImportColumnMapping.class),
        objectMapper.readValue(template.getTypeMapping(), stringMap),
        template.getAccountIdentificationStrategy(),
        template.getFileFormat(),
        template.getPdfLayout() == null
            ? null
            : objectMapper.readValue(template.getPdfLayout(), ImportPdfLayout.class));
  }

  private void apply(
      ImportTemplate template,
      ImportTemplateRequest request,
      ImportTemplateDefinition definition,
      List<String> headerColumns) {
    template.setName(request.name().strip());
    template.setInstitutionCatalogueId(request.institutionCatalogueId());
    template.setEffectiveFrom(businessDateService.today());
    template.setTemplateClass(definition.templateClass());
    template.setDelimiter(definition.delimiter());
    template.setEncoding(definition.encoding());
    template.setDecimalSeparator(definition.decimalSeparator());
    template.setThousandsSeparator(definition.thousandsSeparator());
    template.setDateFormat(definition.dateFormat());
    template.setHeaderRowIndex((short) definition.headerRowIndex());
    template.setPreambleRowCount((short) definition.preambleRowCount());
    template.setTrailingSummaryRowCount((short) definition.trailingSummaryRowCount());
    template.setAmountRepresentation(definition.amountRepresentation());
    template.setCurrencyMode(definition.currencyMode());
    template.setFixedCurrency(definition.fixedCurrency());
    template.setColumnMapping(objectMapper.writeValueAsString(definition.columnMapping()));
    template.setTypeMapping(objectMapper.writeValueAsString(definition.typeMapping()));
    template.setAccountIdentificationStrategy(definition.accountIdentificationStrategy());
    template.setFileFormat(definition.fileFormat());
    if (definition.pdfLayout() != null) {
      template.setPdfLayout(objectMapper.writeValueAsString(definition.pdfLayout()));
    }
    if (headerColumns != null) {
      template.setHeaderColumns(objectMapper.writeValueAsString(headerColumns));
      template.setHeaderFingerprint(ImportFileParserService.fingerprint(headerColumns));
    }
  }

  private List<String> readHeaderColumns(ImportTemplate template) {
    return template.getHeaderColumns() == null
        ? null
        : objectMapper.readValue(template.getHeaderColumns(), stringList);
  }

  private ImportTemplateResponse toResponse(ImportTemplate template) {
    ImportTemplateDefinition definition = definitionOf(template);
    return new ImportTemplateResponse(
        template.getId(),
        template.getTemplateFamilyId(),
        template.getName(),
        template.getInstitutionCatalogueId(),
        definition.templateClass(),
        template.getTemplateVersion(),
        template.getEffectiveFrom(),
        template.isCurrent(),
        template.isActive(),
        template.isShared(),
        !template.isShared() && template.isCurrent(),
        definition.delimiter(),
        definition.encoding(),
        definition.decimalSeparator(),
        definition.thousandsSeparator(),
        definition.dateFormat(),
        definition.headerRowIndex(),
        definition.preambleRowCount(),
        definition.trailingSummaryRowCount(),
        definition.amountRepresentation(),
        definition.currencyMode(),
        definition.fixedCurrency(),
        definition.columnMapping(),
        definition.typeMapping(),
        definition.accountIdentificationStrategy(),
        readHeaderColumns(template),
        template.getHeaderFingerprint(),
        definition.fileFormat(),
        definition.pdfLayout(),
        VersionPreconditionService.persistedVersion(template.getVersion(), VERSIONED_RESOURCE));
  }

  private ImportTemplateTestResponse preview(ImportParseResult result) {
    Locale locale = LocaleContextHolder.getLocale();
    Map<String, Integer> errorCounts = new LinkedHashMap<>();
    List<ImportPreviewRowResponse> rows = new ArrayList<>();
    for (ParsedImportRow row : result.rows()) {
      if (!row.isParsed()) {
        errorCounts.merge(row.errorCode(), 1, Integer::sum);
      }
      if (rows.size() < ImportTemplateTestResponse.PREVIEW_ROWS) {
        rows.add(previewRow(row, locale));
      }
    }
    int errorRows = errorCounts.values().stream().mapToInt(Integer::intValue).sum();
    return new ImportTemplateTestResponse(
        result.headerColumns(),
        result.headerFingerprint(),
        result.rows().size(),
        result.rows().size() - errorRows,
        errorRows,
        errorCounts,
        rows);
  }

  private ImportPreviewRowResponse previewRow(ParsedImportRow row, Locale locale) {
    ImportRowErrorResponse error =
        row.isParsed()
            ? null
            : new ImportRowErrorResponse(
                row.errorCode(),
                row.errorArgs(),
                messageSource.getMessage(
                    MESSAGE_PREFIX + row.errorCode(),
                    row.errorArgs().values().toArray(),
                    row.errorCode(),
                    locale));
    return new ImportPreviewRowResponse(
        row.rowNumber(), row.status(), row.rawData(), row.canonical(), error);
  }
}
