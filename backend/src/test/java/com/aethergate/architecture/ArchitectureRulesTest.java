package com.aethergate.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "com.aethergate", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureRulesTest {

    @ArchTest
    static final ArchRule controllers_do_not_touch_repositories =
            noClasses().that().resideInAPackage("com.aethergate.web..")
                    .should().accessClassesThat().resideInAPackage("..domain.repository..")
                    .because("controllers go through services, which enforce tenant scoping");

    @ArchTest
    static final ArchRule web_does_not_expose_jpa_entities =
            noClasses().that().resideInAPackage("com.aethergate.web..")
                    .should().dependOnClassesThat().areAnnotatedWith(jakarta.persistence.Entity.class)
                    .because("JPA entities never leak into HTTP payloads");

    @ArchTest
    static final ArchRule dtos_are_records =
            classes().that().resideInAPackage("com.aethergate.web.dto..").and().areTopLevelClasses()
                    .should().beRecords();

    @ArchTest
    static final ArchRule no_synchronized_methods =
            methods().should().notHaveModifier(JavaModifier.SYNCHRONIZED)
                    .because("synchronized pins virtual threads; use ReentrantLock");

    @ArchTest
    static final ArchRule no_hardcoded_secrets =
            fields().that().haveNameMatching("(?i).*(password|secret|token|apikey|api_key).*")
                    .and().areStatic().and().areFinal().and().haveRawType(String.class)
                    .should().notBeStatic()
                    .because("secrets come from the environment, never from constants")
                    .allowEmptyShould(true);

    @ArchTest
    static final ArchRule services_do_not_depend_on_web =
            noClasses().that().resideInAPackage("com.aethergate.service..")
                    .should().dependOnClassesThat().resideInAPackage("com.aethergate.web..");

    @ArchTest
    static final ArchRule ledger_is_only_mutated_by_metering =
            noClasses().that().resideOutsideOfPackages("..service.metering..", "..service.tenant..", "..domain..", "..tools..")
                    .should().dependOnClassesThat().haveSimpleName("LedgerEntryRepository")
                    .because("ledger lines are written only by MeteringLedgerService (row lock + idempotency)")
                    .allowEmptyShould(true);
}
