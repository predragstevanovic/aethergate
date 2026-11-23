package com.aethergate.domain.repository;

import com.aethergate.domain.model.Project;
import com.aethergate.domain.model.ProjectStatus;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Runs under RLS: every query only ever sees the current tenant's projects. */
public interface ProjectRepository extends JpaRepository<Project, UUID> {

    List<Project> findAllByOrderByCreatedAtAsc();

    long countByStatus(ProjectStatus status);

    boolean existsByStatusAndNameIgnoreCase(ProjectStatus status, String name);

    boolean existsByStatusAndNameIgnoreCaseAndIdNot(ProjectStatus status, String name, UUID id);
}
