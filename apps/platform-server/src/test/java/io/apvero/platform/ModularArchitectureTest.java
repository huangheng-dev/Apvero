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
    static final ArchRule MODULE_INTERNALS_STAY_PRIVATE = noClasses()
            .that()
            .resideOutsideOfPackages(
                    "io.apvero.platform.application..",
                    "io.apvero.platform.identity..",
                    "io.apvero.platform.governance..",
                    "io.apvero.platform.capability..",
                    "io.apvero.platform.release..",
                    "io.apvero.platform.runtime..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "io.apvero.platform.application.internal..",
                    "io.apvero.platform.identity.internal..",
                    "io.apvero.platform.governance.internal..",
                    "io.apvero.platform.capability.internal..",
                    "io.apvero.platform.release.internal..",
                    "io.apvero.platform.runtime.internal..");
}
