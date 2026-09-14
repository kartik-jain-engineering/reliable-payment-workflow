package com.ledgerflow.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/**
 * Basic module-boundary checks for the modular monolith.
 *
 * <p>These rules are intentionally light for this scaffolding iteration —
 * there is no business code yet, so they mostly assert on the empty
 * {@code package-info.java} skeleton. They exist to fail loudly the moment
 * a future change violates the intended module/layer boundaries, and to be
 * tightened (e.g. restricting cross-module access to {@code application}
 * packages only) as real modules are implemented.
 *
 * <p>{@code allowEmptyShould(true)} is set on every rule below because a
 * {@code package-info.java} with no package-level annotation compiles to no
 * class file at all, so right now these packages contain zero classes for
 * ArchUnit to check. Once real classes land in a module, these rules start
 * actually checking them — no changes needed here.
 */
@AnalyzeClasses(packages = "com.ledgerflow", importOptions = ImportOption.DoNotIncludeTests.class)
class ModularityArchitectureTest {

    @ArchTest
    static final ArchRule top_level_modules_should_be_free_of_cycles =
            slices()
                    .matching("com.ledgerflow.(*)..")
                    .should().beFreeOfCycles()
                    .allowEmptyShould(true);

    @ArchTest
    static final ArchRule domain_should_not_depend_on_infrastructure =
            noClasses()
                    .that().resideInAPackage("..domain..")
                    .should().dependOnClassesThat().resideInAPackage("..infrastructure..")
                    .allowEmptyShould(true);

    @ArchTest
    static final ArchRule domain_should_not_depend_on_spring_web =
            noClasses()
                    .that().resideInAPackage("..domain..")
                    .should().dependOnClassesThat().resideInAnyPackage("org.springframework.web..")
                    .allowEmptyShould(true);
}
