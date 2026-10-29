plugins { id("kotlin-common-conventions") }

dependencies {
    implementation(project(":auth:port"))
    implementation(project(":member:port")) { isTransitive = false }
    implementation(libs.spring.context)
    implementation(libs.spring.tx)
}
