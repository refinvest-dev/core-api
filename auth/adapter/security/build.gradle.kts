plugins { id("spring-adapter-conventions") }

dependencies {
    implementation(project(":auth:port"))
    implementation(project(":member:port")) { isTransitive = false }
    implementation(project(":strategy:port")) { isTransitive = false }
    implementation(project(":backtest:port")) { isTransitive = false }
    implementation(libs.spring.boot.starter.oauth2.client)
    implementation(libs.spring.boot.starter.oauth2.resource.server)
    implementation(libs.spring.boot.starter.security)
    implementation(libs.spring.boot.starter.web)
}
