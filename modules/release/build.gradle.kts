dependencies {
    api(platform(libs.spring.boot.bom))
    api(platform(libs.spring.modulith.bom))

    implementation(project(":modules:application"))
    implementation(project(":modules:capability-registry"))
    implementation(project(":modules:knowledge"))
    api(libs.spring.modulith.api)
    implementation(libs.spring.boot.webmvc)
    implementation(libs.spring.boot.validation)
    implementation(libs.spring.boot.jooq)
    implementation(libs.spring.boot.actuator)
    implementation(libs.networknt.json.schema.validator)

    testImplementation(libs.spring.boot.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.processResources {
    from(rootProject.file("contracts/schemas")) {
        include("release-bundle-manifest.schema.json")
        include("release-bundle-manifest.v1.1.schema.json")
        into("apvero/contracts")
    }
}
