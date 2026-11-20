package com.trackmywealth.backend.controller;

import com.trackmywealth.backend.dto.CategoryResponse;
import com.trackmywealth.backend.dto.CreateCategoryRequest;
import com.trackmywealth.backend.dto.PageResponse;
import com.trackmywealth.backend.dto.UpdateCategoryRequest;
import com.trackmywealth.backend.error.ProblemCode;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import com.trackmywealth.backend.service.CategoryService;
import com.trackmywealth.backend.service.IdempotentCreateService;
import com.trackmywealth.backend.web.IfMatchVersionParser;
import com.trackmywealth.backend.web.Paged;
import com.trackmywealth.backend.web.ProblemCodes;
import com.trackmywealth.backend.web.ResponseHeader;
import com.trackmywealth.backend.web.SendsHeaders;
import com.trackmywealth.backend.web.SuccessStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
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
import org.springframework.web.bind.annotation.RestController;

/** US-08-04: the workspace's category taxonomy. Rules in {@link CategoryService}. */
@RestController
@RequestMapping("/api/v1/categories")
@Tag(name = "Categories")
public class CategoryController {

  private final CategoryService categoryService;
  private final IdempotentCreateService idempotentCreates;

  public CategoryController(
      CategoryService categoryService, IdempotentCreateService idempotentCreates) {
    this.categoryService = categoryService;
    this.idempotentCreates = idempotentCreates;
  }

  /** Tree order; inactive categories only with {@code includeInactive=true}. */
  @Operation(operationId = "listCategories", summary = "List the categories")
  @ProblemCodes({ProblemCode.NOT_FOUND})
  @GetMapping
  public PageResponse<CategoryResponse> list(
      @RequestParam(defaultValue = "false") boolean includeInactive,
      @Paged(
              fixedOrder =
                  "Parent before descendants, siblings by English name ignoring case, then id ascending")
          Pageable pageable,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    return PageResponse.of(categoryService.list(includeInactive, pageable, actor));
  }

  @Operation(operationId = "getCategory", summary = "Read a category")
  @GetMapping("/{id}")
  @SendsHeaders(ResponseHeader.ETAG)
  public ResponseEntity<CategoryResponse> get(
      @PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    CategoryResponse category = categoryService.get(id, actor);
    return withEtag(ResponseEntity.ok(), category);
  }

  @SuccessStatus(HttpStatus.CREATED)
  @Operation(operationId = "createCategory", summary = "Create a category")
  @ProblemCodes({
    ProblemCode.NOT_FOUND,
    ProblemCode.CONFLICT,
    ProblemCode.RETRY,
    ProblemCode.UNPROCESSABLE,
    ProblemCode.IDEMPOTENCY_KEY_IN_USE,
    ProblemCode.IDEMPOTENCY_KEY_MISMATCH
  })
  @PostMapping
  @SendsHeaders(ResponseHeader.ETAG)
  public ResponseEntity<CategoryResponse> create(
      @Valid @RequestBody CreateCategoryRequest request,
      @Parameter(
              description = IdempotentCreateService.HEADER_DESCRIPTION,
              schema = @Schema(type = "string", format = "uuid"))
          @RequestHeader(value = IdempotentCreateService.HEADER, required = false)
          String idempotencyKey,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    CategoryResponse created =
        idempotentCreates.create(
            "createCategory",
            idempotencyKey,
            actor,
            request,
            CategoryResponse.class,
            () -> categoryService.create(request, actor));
    return withEtag(ResponseEntity.status(HttpStatus.CREATED), created);
  }

  @Operation(operationId = "updateCategory", summary = "Rename a category or change its parent")
  @ProblemCodes({ProblemCode.CONFLICT, ProblemCode.RETRY, ProblemCode.UNPROCESSABLE})
  @PutMapping("/{id}")
  @SendsHeaders(ResponseHeader.ETAG)
  public ResponseEntity<CategoryResponse> update(
      @PathVariable UUID id,
      @Valid @RequestBody UpdateCategoryRequest request,
      @RequestHeader(value = IfMatchVersionParser.HEADER, required = false) String ifMatch,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    CategoryResponse updated =
        categoryService.update(id, request, IfMatchVersionParser.parse(ifMatch), actor);
    return withEtag(ResponseEntity.ok(), updated);
  }

  /** Cascades to every subcategory. */
  @Operation(operationId = "deactivateCategory", summary = "Deactivate a category")
  @ProblemCodes({ProblemCode.UNPROCESSABLE})
  @PostMapping("/{id}/deactivate")
  @SendsHeaders(ResponseHeader.ETAG)
  public ResponseEntity<CategoryResponse> deactivate(
      @PathVariable UUID id,
      @RequestHeader(value = IfMatchVersionParser.HEADER, required = false) String ifMatch,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    CategoryResponse deactivated =
        categoryService.deactivate(id, IfMatchVersionParser.parse(ifMatch), actor);
    return withEtag(ResponseEntity.ok(), deactivated);
  }

  /** This category only; its parent must be active. */
  @Operation(operationId = "activateCategory", summary = "Reactivate a category")
  @ProblemCodes({ProblemCode.UNPROCESSABLE})
  @PostMapping("/{id}/activate")
  @SendsHeaders(ResponseHeader.ETAG)
  public ResponseEntity<CategoryResponse> activate(
      @PathVariable UUID id,
      @RequestHeader(value = IfMatchVersionParser.HEADER, required = false) String ifMatch,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    CategoryResponse activated =
        categoryService.activate(id, IfMatchVersionParser.parse(ifMatch), actor);
    return withEtag(ResponseEntity.ok(), activated);
  }

  /** 204 only for a never-used workspace category; otherwise 409, deactivate instead. */
  @SuccessStatus(HttpStatus.NO_CONTENT)
  @Operation(operationId = "deleteCategory", summary = "Delete an unused category")
  @ProblemCodes({ProblemCode.CONFLICT})
  @DeleteMapping("/{id}")
  public ResponseEntity<Void> delete(
      @PathVariable UUID id,
      @RequestHeader(value = IfMatchVersionParser.HEADER, required = false) String ifMatch,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    categoryService.delete(id, IfMatchVersionParser.parse(ifMatch), actor);
    return ResponseEntity.noContent().build();
  }

  private static ResponseEntity<CategoryResponse> withEtag(
      ResponseEntity.BodyBuilder builder, CategoryResponse body) {
    return builder.eTag(IfMatchVersionParser.toEtag(body.version())).body(body);
  }
}
