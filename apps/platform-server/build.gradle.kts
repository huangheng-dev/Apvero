plugins {
    alias(libs.plugins.spring.boot)
}

dependencies {
    implementation(platform(libs.spring.boot.bom))
    implementation(platform(libs.spring.ai.bom))
    implementation(platform(libs.spring.modulith.bom))

    implementation(project(":modules:application"))
    implementation(project(":modules:identity"))
    implementation(project(":modules:governance"))
    implementation(project(":modules:capability-registry"))
    implementation(project(":modules:release"))
    implementation(project(":modules:runtime"))

    implementation(libs.spring.boot.webmvc)
    implementation(libs.spring.boot.validation)
    implementation(libs.spring.boot.security)
    implementation(libs.spring.boot.jooq)
    implementation(libs.spring.boot.actuator)
    implementation(libs.spring.boot.flyway)
    implementation(libs.spring.modulith.core)
    implementation(libs.spring.ai.model)
    runtimeOnly(libs.flyway.postgresql)
    runtimeOnly(libs.postgresql)

    testImplementation(libs.spring.boot.test)
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.spring.boot.webmvc.test)
    testImplementation(libs.spring.modulith.test)
    testImplementation(libs.archunit)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
    testRuntimeOnly(libs.junit.platform.launcher)
}

springBoot {
    buildInfo()
}

tasks.jar {
    enabled = false
}
