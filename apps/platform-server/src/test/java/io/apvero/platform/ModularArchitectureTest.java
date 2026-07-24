package io.apvero.platform;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

@AnalyzeClasses(packages = "io.apvero.platform")
class ModularArchitectureTest {

    @Test
    void springModulithBoundariesAreValid() {
        ApplicationModules.of(ApveroApplication.class).verify();
    }

    @ArchTest
    static final ArchRule CORE_DOES_NOT_IMPORT_PROVIDER_SDKS = noClasses()
            .that()
            .resideInAnyPackage(
                    "io.apvero.platform.application..",
                    "io.apvero.platform.identity..",
                    "io.apvero.platform.governance..",
                    "io.apvero.platform.capability..",
                    "io.apvero.platform.knowledge..",
                    "io.apvero.platform.release..",
                    "io.apvero.platform.runtime..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "dev.langchain4j..",
                    "com.alibaba.cloud.ai..",
                    "com.openai..",
                    "com.anthropic..");

    @ArchTest
    static final ArchRule EMBEDDING_PUBLIC_API_STAYS_PROVIDER_NEUTRAL = noClasses()
            .that()
            .resideInAPackage("io.apvero.platform.capability")
            .and()
            .haveSimpleNameStartingWith("Embedding")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "org.springframework.ai..",
                    "dev.langchain4j..",
                    "com.alibaba.cloud.ai..",
                    "com.openai..",
                    "com.anthropic..");

    @ArchTest
    static final ArchRule MODULE_INTERNALS_STAY_PRIVATE = noClasses()
            .that()
            .resideOutsideOfPackages(
                    "io.apvero.platform.application..",
                    "io.apvero.platform.identity..",
                    "io.apvero.platform.governance..",
                    "io.apvero.platform.capability..",
                    "io.apvero.platform.knowledge..",
                    "io.apvero.platform.release..",
                    "io.apvero.platform.runtime..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "io.apvero.platform.application.internal..",
                    "io.apvero.platform.identity.internal..",
                    "io.apvero.platform.governance.internal..",
                    "io.apvero.platform.capability.internal..",
                    "io.apvero.platform.knowledge.internal..",
                    "io.apvero.platform.release.internal..",
                    "io.apvero.platform.runtime.internal..");

    @ArchTest
    static final ArchRule KNOWLEDGE_DEPENDS_ONLY_ON_APPROVED_MODULES = noClasses()
            .that()
            .resideInAnyPackage("io.apvero.platform.knowledge..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "io.apvero.platform.application..",
                    "io.apvero.platform.release..",
                    "io.apvero.platform.runtime..",
                    "io.apvero.platform.evaluation..",
                    "io.apvero.platform.extension..");

    @ArchTest
    static final ArchRule KNOWLEDGE_INTERNALS_STAY_PRIVATE = noClasses()
            .that()
            .resideOutsideOfPackage("io.apvero.platform.knowledge..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("io.apvero.platform.knowledge.internal..");

    @ArchTest
    static final ArchRule CAPABILITY_INTERNALS_STAY_PRIVATE = noClasses()
            .that()
            .resideOutsideOfPackage("io.apvero.platform.capability..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("io.apvero.platform.capability.internal..");

    @ArchTest
    static final ArchRule GOVERNANCE_INTERNALS_STAY_PRIVATE = noClasses()
            .that()
            .resideOutsideOfPackage("io.apvero.platform.governance..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("io.apvero.platform.governance.internal..");
}
