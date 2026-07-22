dependencies {
    api(platform(libs.spring.boot.bom))
    api(platform(libs.spring.modulith.bom))

    implementation(project(":modules:identity"))
    implementation(project(":modules:capability-registry"))
    implementation(project(":modules:governance"))
    api(libs.spring.modulith.api)
    implementation(libs.spring.boot.actuator)
    implementation("com.fasterxml.jackson.core:jackson-annotations")

    testImplementation(libs.spring.boot.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}
