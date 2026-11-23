package com.aethergate.domain.repository;

import com.aethergate.domain.model.GuardrailRule;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Runs under RLS: only the current tenant's rules are ever visible. */
public interface GuardrailRuleRepository extends JpaRepository<GuardrailRule, UUID> {

    List<GuardrailRule> findAllByOrderByCreatedAtAsc();

    List<GuardrailRule> findByEnabledTrueOrderByCreatedAtAsc();

    boolean existsByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCaseAndIdNot(String name, UUID id);
}
