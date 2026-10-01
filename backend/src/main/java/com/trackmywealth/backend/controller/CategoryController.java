package com.trackmywealth.backend.controller;

import com.trackmywealth.backend.dto.CategoryResponse;
import com.trackmywealth.backend.dto.CreateCategoryRequest;
import com.trackmywealth.backend.dto.UpdateCategoryRequest;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import com.trackmywealth.backend.service.CategoryService;
import com.trackmywealth.backend.web.IfMatchVersionParser;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
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
public class CategoryController {

  private final CategoryService categoryService;

  public CategoryController(CategoryService categoryService) {
    this.categoryService = categoryService;
  }

  /** Tree order; inactive categories only with {@code includeInactive=true}. */
  @GetMapping
  public List<CategoryResponse> list(
      @RequestParam(defaultValue = "false") boolean includeInactive,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    return categoryService.list(includeInactive, actor);
  }

  @GetMapping("/{id}")
  public ResponseEntity<CategoryResponse> get(
      @PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    CategoryResponse category = categoryService.get(id, actor);
    return withEtag(ResponseEntity.ok(), category);
  }

  @PostMapping
  public ResponseEntity<CategoryResponse> create(
      @Valid @RequestBody CreateCategoryRequest request,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    CategoryResponse created = categoryService.create(request, actor);
    return withEtag(ResponseEntity.status(HttpStatus.CREATED), created);
  }

  @PutMapping("/{id}")
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
  @PostMapping("/{id}/deactivate")
  public ResponseEntity<CategoryResponse> deactivate(
      @PathVariable UUID id,
      @RequestHeader(value = IfMatchVersionParser.HEADER, required = false) String ifMatch,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    CategoryResponse deactivated =
        categoryService.deactivate(id, IfMatchVersionParser.parse(ifMatch), actor);
    return withEtag(ResponseEntity.ok(), deactivated);
  }

  /** This category only; its parent must be active. */
  @PostMapping("/{id}/activate")
  public ResponseEntity<CategoryResponse> activate(
      @PathVariable UUID id,
      @RequestHeader(value = IfMatchVersionParser.HEADER, required = false) String ifMatch,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    CategoryResponse activated =
        categoryService.activate(id, IfMatchVersionParser.parse(ifMatch), actor);
    return withEtag(ResponseEntity.ok(), activated);
  }

  /** 204 only for a never-used workspace category; otherwise 409, deactivate instead. */
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
