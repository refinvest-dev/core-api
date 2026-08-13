plugins { id("spring-adapter-conventions") }
dependencies {
    api(project(":backtest:port"))
    implementation(project(":shared:infrastructure"))
    implementation(libs.spring.context)
}
