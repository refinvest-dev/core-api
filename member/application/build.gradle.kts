plugins { id("kotlin-common-conventions") }

dependencies {
    implementation(project(":member:port"))
    implementation(libs.spring.context)
    implementation(libs.spring.tx)
}
