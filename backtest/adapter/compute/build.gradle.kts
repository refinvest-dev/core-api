plugins { id("spring-adapter-conventions") }
dependencies {
    implementation(project(":backtest:port"))
    implementation("io.micrometer:micrometer-core")
    implementation(libs.spring.boot.starter.web)
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.jackson.module.kotlin)
}
