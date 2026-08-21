plugins { id("spring-adapter-conventions") }

dependencies {
    implementation(project(":auth:port"))
    implementation(project(":auth:application"))
    implementation(project(":member:port"))
    implementation(project(":strategy:port"))
    implementation(project(":backtest:port"))
    implementation(libs.spring.boot.starter.oauth2.client)
    implementation(libs.spring.boot.starter.oauth2.resource.server)
    implementation(libs.spring.boot.starter.security)
    implementation(libs.spring.boot.starter.web)
}
