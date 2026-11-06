plugins { id("kotlin-common-conventions") }
dependencies {
    implementation(project(":backtest:port"))
    implementation(project(":strategy:port")) { isTransitive = false }
    implementation(project(":subscription:port")) { isTransitive = false }
    implementation(libs.spring.context)
    implementation(libs.spring.tx)
}
