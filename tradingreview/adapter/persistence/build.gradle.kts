plugins { id("spring-adapter-conventions") }
dependencies {
    implementation(project(":tradingreview:port"))
    implementation(libs.spring.boot.starter.jdbc)
    implementation(libs.postgresql)
    implementation("io.micrometer:micrometer-core")
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(project(":tradingreview:application"))
    testImplementation(project(":tradingreview:adapter:storage"))
}
