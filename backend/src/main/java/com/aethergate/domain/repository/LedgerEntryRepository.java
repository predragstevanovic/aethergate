package com.aethergate.domain.repository;

import com.aethergate.domain.model.EntryType;
import com.aethergate.domain.model.LedgerEntry;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, UUID> {

    Optional<LedgerEntry> findByTenantIdAndRequestIdAndEntryType(UUID tenantId, String requestId, EntryType entryType);

    List<LedgerEntry> findByTenantIdAndRequestIdOrderByCreatedAtAsc(UUID tenantId, String requestId);

    Page<LedgerEntry> findByTenantIdOrderByCreatedAtDesc(UUID tenantId, Pageable pageable);

    Page<LedgerEntry> findByTenantIdAndEntryTypeOrderByCreatedAtDesc(UUID tenantId, EntryType type, Pageable pageable);

    @Query("""
            select coalesce(sum(e.amountUsd), 0) from LedgerEntry e
            where e.tenantId = :tenantId and e.entryType = :type and e.createdAt >= :since
            """)
    BigDecimal sumSince(@Param("tenantId") UUID tenantId, @Param("type") EntryType type, @Param("since") Instant since);
}
