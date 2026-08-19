plugins { id("kotlin-common-conventions") }
dependencies {
    implementation(project(":backtest:port"))
    implementation(project(":strategy:port"))
    implementation(libs.spring.context)
    implementation(libs.spring.tx)
}
