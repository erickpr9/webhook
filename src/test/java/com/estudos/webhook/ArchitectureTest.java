package com.estudos.webhook;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.base.DescribedPredicate;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Garante a regra de dependência da arquitetura hexagonal:
 * {@code api → application → domain ← infrastructure}.
 */
class ArchitectureTest {

    private static final String BASE = "com.estudos.webhook";

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(BASE);

    @Test
    void domainDependsOnlyOnTheJdk() {
        classes().that().resideInAPackage(BASE + ".domain..")
                .should().onlyDependOnClassesThat().resideInAnyPackage(BASE + ".domain..", "java..")
                .check(CLASSES);
    }

    @Test
    void applicationDoesNotKnowFrameworksOrAdapters() {
        classes().that().resideInAPackage(BASE + ".application..")
                .should().onlyDependOnClassesThat()
                .resideInAnyPackage(BASE + ".application..", BASE + ".domain..", "java..", "org.slf4j..")
                .check(CLASSES);
    }

    @Test
    void infrastructureDoesNotDependOnTheApi() {
        noClasses().that().resideInAPackage(BASE + ".infrastructure..")
                .should().dependOnClassesThat().resideInAnyPackage(BASE + ".api..", BASE + ".dev..")
                .check(CLASSES);
    }

    @Test
    void apiDoesNotDependOnInfrastructure() {
        noClasses().that().resideInAPackage(BASE + ".api..")
                .should().dependOnClassesThat().resideInAnyPackage(BASE + ".infrastructure..", BASE + ".dev..")
                .check(CLASSES);
    }

    @Test
    void apiOnlyTouchesDomainExceptionsAndValueEnums() {
        DescribedPredicate<JavaClass> domainEntitiesOrServices = new DescribedPredicate<>(
                "domain classes other than exceptions and enums") {
            @Override
            public boolean test(JavaClass javaClass) {
                return javaClass.getPackageName().startsWith(BASE + ".domain")
                        && !javaClass.getPackageName().startsWith(BASE + ".domain.exception")
                        && !javaClass.isEnum();
            }
        };
        noClasses().that().resideInAPackage(BASE + ".api..")
                .should().dependOnClassesThat(domainEntitiesOrServices)
                .check(CLASSES);
    }

    @Test
    void devToolsStayOutOfTheCoreLayers() {
        noClasses().that().resideInAnyPackage(BASE + ".domain..", BASE + ".application..")
                .should().dependOnClassesThat().resideInAPackage(BASE + ".dev..")
                .check(CLASSES);
    }
}
