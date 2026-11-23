package com.aethergate.domain.repository;

import com.aethergate.domain.model.Tenant;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TenantRepository extends JpaRepository<Tenant, UUID> {

    /** {@code SELECT ... FOR UPDATE}: serialises every balance mutation of one tenant. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Tenant t where t.id = :id")
    Optional<Tenant> findByIdForUpdate(@Param("id") UUID id);

    Optional<Tenant> findByOwnerUserId(UUID ownerUserId);

    Optional<Tenant> findBySlug(String slug);

    boolean existsBySlug(String slug);

    @Modifying
    @Query("update Tenant t set t.ownerUserId = :owner where t.id = :id")
    int rebindOwner(@Param("id") UUID id, @Param("owner") UUID owner);
}
