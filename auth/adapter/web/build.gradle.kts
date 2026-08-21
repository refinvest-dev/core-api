plugins { id("spring-adapter-conventions") }

dependencies {
    implementation(project(":auth:port"))
    implementation(project(":auth:adapter:security"))
    implementation(project(":subscription:port"))
    implementation(libs.spring.boot.starter.security)
    implementation(libs.spring.boot.starter.web)
}
