plugins { id("jpa-adapter-conventions") }
dependencies {
    implementation(project(":tradingreview:port"))
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.postgresql)
    implementation("io.micrometer:micrometer-core")
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(project(":tradingreview:application"))
    testImplementation(project(":tradingreview:adapter:storage"))
    testImplementation(project(":tradingreview:adapter:security"))
}
