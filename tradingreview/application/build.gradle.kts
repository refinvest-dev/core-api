plugins { id("kotlin-common-conventions") }
dependencies {
    implementation(project(":tradingreview:port"))
    implementation(libs.spring.context)
    implementation(libs.spring.tx)
}
