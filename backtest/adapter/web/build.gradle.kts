plugins { id("spring-adapter-conventions") }

dependencies {
    implementation(project(":backtest:port"))
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.jackson.module.kotlin)
}
