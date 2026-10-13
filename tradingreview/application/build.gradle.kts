plugins { id("kotlin-common-conventions") }
dependencies {
    implementation(platform(libs.spring.boot.dependencies))
    implementation(project(":tradingreview:port"))
    implementation(libs.spring.context)
    implementation(libs.spring.tx)
    implementation(libs.jackson.module.kotlin)
}
