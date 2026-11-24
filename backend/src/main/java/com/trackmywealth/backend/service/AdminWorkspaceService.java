package com.trackmywealth.backend.service;

import com.trackmywealth.backend.dto.AdminWorkspaceSummaryResponse;
import com.trackmywealth.backend.dto.PagingValues;
import com.trackmywealth.backend.repository.WorkspaceRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminWorkspaceService {
  private final WorkspaceRepository workspaces;

  public AdminWorkspaceService(WorkspaceRepository workspaces) {
    this.workspaces = workspaces;
  }

  @Transactional(readOnly = true)
  public Page<AdminWorkspaceSummaryResponse> list(Pageable pageable) {
    PagingValues.requireBoundedFixedOrder(pageable);
    return PagingValues.jpaPage(
            pageable, () -> workspaces.findAdminMetadata(pageable), workspaces::countAdminMetadata)
        .map(row -> new AdminWorkspaceSummaryResponse(row.getId(), row.getName()));
  }
}
