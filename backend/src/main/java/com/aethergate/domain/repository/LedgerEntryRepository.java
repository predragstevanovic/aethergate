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

    // V8 spend caps: settled spend and in-flight holds per API key, end user and agent session

    @Query("""
            select coalesce(sum(e.amountUsd), 0) from LedgerEntry e
            where e.tenantId = :tenantId and e.apiKeyId = :keyId and e.entryType = :type and e.createdAt >= :since
            """)
    BigDecimal sumKeySince(@Param("tenantId") UUID tenantId, @Param("keyId") UUID keyId, @Param("type") EntryType type,
                           @Param("since") Instant since);

    @Query("""
            select coalesce(sum(r.amountUsd), 0) from LedgerEntry r
            where r.tenantId = :tenantId and r.apiKeyId = :keyId
              and r.entryType = com.aethergate.domain.model.EntryType.RESERVE
              and not exists (select 1 from LedgerEntry c
                              where c.tenantId = r.tenantId and c.requestId = r.requestId
                                and c.entryType in (com.aethergate.domain.model.EntryType.SETTLE,
                                                    com.aethergate.domain.model.EntryType.RELEASE))
            """)
    BigDecimal openKeyHolds(@Param("tenantId") UUID tenantId, @Param("keyId") UUID keyId);

    @Query("""
            select coalesce(sum(e.amountUsd), 0) from LedgerEntry e
            where e.tenantId = :tenantId and e.endUserId = :user and e.entryType = :type and e.createdAt >= :since
            """)
    BigDecimal sumEndUserSince(@Param("tenantId") UUID tenantId, @Param("user") String endUser, @Param("type") EntryType type,
                               @Param("since") Instant since);

    @Query("""
            select coalesce(sum(r.amountUsd), 0) from LedgerEntry r
            where r.tenantId = :tenantId and r.endUserId = :user
              and r.entryType = com.aethergate.domain.model.EntryType.RESERVE
              and not exists (select 1 from LedgerEntry c
                              where c.tenantId = r.tenantId and c.requestId = r.requestId
                                and c.entryType in (com.aethergate.domain.model.EntryType.SETTLE,
                                                    com.aethergate.domain.model.EntryType.RELEASE))
            """)
    BigDecimal openEndUserHolds(@Param("tenantId") UUID tenantId, @Param("user") String endUser);

    @Query("""
            select coalesce(sum(e.amountUsd), 0) from LedgerEntry e
            where e.tenantId = :tenantId and e.sessionId = :session and e.entryType = :type
            """)
    BigDecimal sumSession(@Param("tenantId") UUID tenantId, @Param("session") String session, @Param("type") EntryType type);

    @Query("""
            select count(e) from LedgerEntry e
            where e.tenantId = :tenantId and e.sessionId = :session
              and e.entryType = com.aethergate.domain.model.EntryType.RESERVE
            """)
    long countSessionHolds(@Param("tenantId") UUID tenantId, @Param("session") String session);

    @Query("""
            select coalesce(sum(r.amountUsd), 0) from LedgerEntry r
            where r.tenantId = :tenantId and r.sessionId = :session
              and r.entryType = com.aethergate.domain.model.EntryType.RESERVE
              and not exists (select 1 from LedgerEntry c
                              where c.tenantId = r.tenantId and c.requestId = r.requestId
                                and c.entryType in (com.aethergate.domain.model.EntryType.SETTLE,
                                                    com.aethergate.domain.model.EntryType.RELEASE))
            """)
    BigDecimal openSessionHolds(@Param("tenantId") UUID tenantId, @Param("session") String session);

    @Query("""
            select e.apiKeyId, coalesce(sum(e.amountUsd), 0) from LedgerEntry e
            where e.tenantId = :tenantId and e.apiKeyId is not null and e.entryType = :type and e.createdAt >= :since
            group by e.apiKeyId
            """)
    List<Object[]> sumByKeySince(@Param("tenantId") UUID tenantId, @Param("type") EntryType type, @Param("since") Instant since);
}
