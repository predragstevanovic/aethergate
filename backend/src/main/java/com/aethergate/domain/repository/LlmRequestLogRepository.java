package com.aethergate.domain.repository;

import com.aethergate.domain.model.LlmRequestLog;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LlmRequestLogRepository extends JpaRepository<LlmRequestLog, UUID> {

    List<LlmRequestLog> findTop25ByTenantIdOrderByCreatedAtDesc(UUID tenantId);
}
