package com.trackmywealth.backend.controller;

import com.trackmywealth.backend.dto.AdminWorkspaceSummaryResponse;
import com.trackmywealth.backend.dto.PageResponse;
import com.trackmywealth.backend.service.AdminWorkspaceService;
import com.trackmywealth.backend.web.Paged;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Technical workspace metadata for system administrators. */
@RestController
@Tag(name = "Admin")
public class AdminWorkspaceController {
  private final AdminWorkspaceService workspaces;

  public AdminWorkspaceController(AdminWorkspaceService workspaces) {
    this.workspaces = workspaces;
  }

  @Operation(operationId = "listAdminWorkspaces", summary = "List workspace metadata")
  @GetMapping("/api/v1/admin/workspaces")
  public PageResponse<AdminWorkspaceSummaryResponse> list(
      @Paged(fixedOrder = "id ascending") Pageable pageable) {
    return PageResponse.of(workspaces.list(pageable));
  }
}
