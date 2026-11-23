package com.aethergate.domain.repository;

import com.aethergate.domain.model.ProviderConfig;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Tenant-scoped: callers run under RLS, so "all" means "all of the current tenant". */
public interface ProviderConfigRepository extends JpaRepository<ProviderConfig, UUID> {

    List<ProviderConfig> findAllByOrderByPriorityOrderAsc();

    List<ProviderConfig> findByEnabledTrueOrderByPriorityOrderAsc();
}
