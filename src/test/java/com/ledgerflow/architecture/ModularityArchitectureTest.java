package com.ledgerflow.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/**
 * Basic module/layer-boundary checks for the modular monolith.
 *
 * <p>Each top-level package under {@code com.ledgerflow} is a module (only
 * {@code order} has real code so far), layered internally as {@code api}
 * (controllers, request/response mapping) → {@code service} (use cases) →
 * {@code repo} (Spring Data repositories), all depending on {@code model}
 * (entities, DTOs and enums) — dependencies point inward, and {@code model}
 * must never depend back out on any of the other three.
 *
 * <p>{@code allowEmptyShould(true)} keeps a rule from failing when a module
 * has no classes in a given layer yet, so these checks stay green for
 * future modules until they grow real code.
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
    static final ArchRule model_should_not_depend_on_outer_layers =
            noClasses()
                    .that().resideInAPackage("..model..")
                    .should().dependOnClassesThat().resideInAnyPackage("..api..", "..service..", "..repo..")
                    .allowEmptyShould(true);

    @ArchTest
    static final ArchRule repo_should_not_depend_on_api =
            noClasses()
                    .that().resideInAPackage("..repo..")
                    .should().dependOnClassesThat().resideInAPackage("..api..")
                    .allowEmptyShould(true);

    @ArchTest
    static final ArchRule model_should_not_depend_on_spring_web =
            noClasses()
                    .that().resideInAPackage("..model..")
                    .should().dependOnClassesThat().resideInAnyPackage("org.springframework.web..")
                    .allowEmptyShould(true);
}
