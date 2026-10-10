plugins { id("spring-adapter-conventions") }
dependencies {
    implementation(project(":tradingreview:port"))
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.jackson.module.kotlin)
    testImplementation(libs.spring.boot.starter.webmvc.test)
}
