package com.trackmywealth.backend.controller;

import com.trackmywealth.backend.dto.CategoryResponse;
import com.trackmywealth.backend.dto.CreateCategoryRequest;
import com.trackmywealth.backend.dto.UpdateCategoryRequest;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import com.trackmywealth.backend.service.CategoryService;
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
  public CategoryResponse get(
      @PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    return categoryService.get(id, actor);
  }

  @PostMapping
  public ResponseEntity<CategoryResponse> create(
      @Valid @RequestBody CreateCategoryRequest request,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    return ResponseEntity.status(HttpStatus.CREATED).body(categoryService.create(request, actor));
  }

  @PutMapping("/{id}")
  public CategoryResponse update(
      @PathVariable UUID id,
      @Valid @RequestBody UpdateCategoryRequest request,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    return categoryService.update(id, request, actor);
  }

  /** Cascades to every subcategory. */
  @PostMapping("/{id}/deactivate")
  public CategoryResponse deactivate(
      @PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    return categoryService.deactivate(id, actor);
  }

  /** This category only; its parent must be active. */
  @PostMapping("/{id}/activate")
  public CategoryResponse activate(
      @PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    return categoryService.activate(id, actor);
  }

  /** 204 only for a never-used workspace category; otherwise 409, deactivate instead. */
  @DeleteMapping("/{id}")
  public ResponseEntity<Void> delete(
      @PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    categoryService.delete(id, actor);
    return ResponseEntity.noContent().build();
  }
}
