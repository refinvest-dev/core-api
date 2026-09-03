plugins { id("spring-adapter-conventions") }
dependencies {
    implementation(project(":asset:port"))
    implementation(libs.spring.boot.starter.web)
}
