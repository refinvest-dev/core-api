plugins { id("spring-adapter-conventions") }
dependencies {
    implementation(project(":asset:port"))
    implementation(libs.spring.boot.starter.web)
    implementation(libs.jackson.databind)
    implementation(libs.jackson.module.kotlin)
    testImplementation(libs.spring.boot.starter.test)
}
