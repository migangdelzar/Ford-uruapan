package com.ford.service.architecture;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(packages = "com.ford.service")
class PackageBoundaryTest {

    @ArchTest
    static final ArchRule domainMustNotDependOnSpring = noClasses()
            .that().resideInAnyPackage("..catalog.domain..", "..finance.domain..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("org.springframework..", "org.hibernate..")
            .allowEmptyShould(true);
}
