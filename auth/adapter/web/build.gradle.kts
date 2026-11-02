plugins { id("spring-adapter-conventions") }

dependencies {
    implementation(project(":auth:port"))
    implementation(project(":subscription:port")) { isTransitive = false }
    implementation(libs.spring.boot.starter.security)
    implementation(libs.spring.boot.starter.oauth2.client)
    implementation(libs.spring.boot.starter.oauth2.resource.server)
    implementation(libs.spring.boot.starter.web)
    testImplementation(libs.spring.boot.starter.test)
}
