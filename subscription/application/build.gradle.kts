plugins { id("kotlin-common-conventions") }

dependencies {
    implementation(project(":subscription:port"))
    implementation(project(":backtest:port"))
    implementation(libs.spring.context)
    implementation(libs.spring.tx)
}
