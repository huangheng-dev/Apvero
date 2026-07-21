dependencies {
    api(platform(libs.spring.boot.bom))
    api(platform(libs.spring.modulith.bom))
    implementation(platform(libs.spring.ai.bom))

    implementation(project(":modules:application"))
    implementation(project(":modules:release"))
    implementation(project(":modules:capability-registry"))
    api(libs.spring.modulith.api)
    implementation(libs.spring.boot.webmvc)
    implementation(libs.spring.boot.validation)
    implementation(libs.spring.boot.jooq)
    implementation(libs.spring.boot.actuator)
    implementation(libs.spring.ai.model)
    implementation(libs.spring.ai.openai)

    testImplementation(libs.spring.boot.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}
