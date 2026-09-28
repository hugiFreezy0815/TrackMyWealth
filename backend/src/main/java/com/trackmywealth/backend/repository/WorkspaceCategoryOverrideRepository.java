package com.trackmywealth.backend.repository;

import com.trackmywealth.backend.entity.WorkspaceCategoryOverride;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface WorkspaceCategoryOverrideRepository
    extends JpaRepository<WorkspaceCategoryOverride, UUID> {

  List<WorkspaceCategoryOverride> findByWorkspaceId(UUID workspaceId);
}
