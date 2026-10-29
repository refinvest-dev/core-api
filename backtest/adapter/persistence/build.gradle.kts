plugins { id("jpa-adapter-conventions") }

dependencies {
    implementation(project(":backtest:port"))
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.jackson.databind)
    implementation(libs.jackson.module.kotlin)
    implementation("io.micrometer:micrometer-core")
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(project(":backtest:application"))
    testImplementation(project(":backtest:adapter:compute"))
    testImplementation(project(":strategy:port"))
    testRuntimeOnly(libs.postgresql)
}
