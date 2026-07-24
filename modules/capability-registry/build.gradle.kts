dependencies {
    api(platform(libs.spring.boot.bom))
    api(platform(libs.spring.modulith.bom))
    implementation(platform(libs.spring.ai.bom))

    implementation(project(":modules:identity"))
    implementation(project(":modules:governance"))
    api(libs.spring.modulith.api)
    implementation(libs.spring.ai.model)
    implementation(libs.spring.boot.webmvc)
    implementation(libs.spring.boot.validation)
    implementation(libs.spring.boot.jooq)

    testImplementation(libs.spring.boot.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}
