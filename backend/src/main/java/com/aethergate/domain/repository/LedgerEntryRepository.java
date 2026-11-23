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

    @Query("""
            select coalesce(sum(e.amountUsd), 0) from LedgerEntry e
            where e.tenantId = :tenantId and e.projectId = :projectId and e.entryType = :type and e.createdAt >= :since
            """)
    BigDecimal sumProjectSince(@Param("tenantId") UUID tenantId, @Param("projectId") UUID projectId,
                               @Param("type") EntryType type, @Param("since") Instant since);

    /** Holds of the project that are still in flight: a RESERVE with no SETTLE/RELEASE for its request yet. */
    @Query("""
            select coalesce(sum(r.amountUsd), 0) from LedgerEntry r
            where r.tenantId = :tenantId and r.projectId = :projectId
              and r.entryType = com.aethergate.domain.model.EntryType.RESERVE
              and not exists (select 1 from LedgerEntry c
                              where c.tenantId = r.tenantId and c.requestId = r.requestId
                                and c.entryType in (com.aethergate.domain.model.EntryType.SETTLE,
                                                    com.aethergate.domain.model.EntryType.RELEASE))
            """)
    BigDecimal openProjectHolds(@Param("tenantId") UUID tenantId, @Param("projectId") UUID projectId);
}
