dependencies {
    api(platform(libs.spring.boot.bom))
    api(platform(libs.spring.modulith.bom))

    api(libs.spring.modulith.api)
    implementation(libs.spring.boot.webmvc)
    implementation(libs.spring.boot.validation)
    implementation(libs.spring.boot.security)
    implementation(libs.spring.boot.jooq)

    testImplementation(libs.spring.boot.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}
