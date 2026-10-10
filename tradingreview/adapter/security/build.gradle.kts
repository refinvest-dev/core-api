plugins { id("spring-adapter-conventions") }
dependencies {
    implementation(project(":tradingreview:port"))
    implementation(project(":member:port"))
    implementation(libs.spring.boot.starter.oauth2.resource.server)
    implementation(libs.spring.boot.starter.security)
}
