plugins { id("spring-adapter-conventions") }
dependencies {
    api(project(":strategy:port"))
    implementation(project(":shared:infrastructure"))
    implementation(libs.spring.context)
}
