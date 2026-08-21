plugins { id("kotlin-common-conventions") }

dependencies {
    implementation(project(":auth:port"))
    implementation(project(":member:port"))
    implementation(libs.spring.context)
    implementation(libs.spring.tx)
}
