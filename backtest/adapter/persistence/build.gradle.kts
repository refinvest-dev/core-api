plugins { id("jpa-adapter-conventions") }

dependencies {
    implementation(project(":backtest:port"))
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.jackson.databind)
    implementation(libs.jackson.module.kotlin)
}
