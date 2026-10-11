plugins { id("spring-adapter-conventions") }
dependencies {
    implementation(project(":tradingreview:port"))
    implementation(libs.spring.boot.starter.jdbc)
    testImplementation(libs.spring.boot.starter.test)
}
