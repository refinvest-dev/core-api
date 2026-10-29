plugins { id("kotlin-common-conventions") }

dependencies {
    implementation(project(":member:port"))
    implementation(project(":subscription:port")) { isTransitive = false }
    implementation(libs.spring.context)
    implementation(libs.spring.tx)
}
