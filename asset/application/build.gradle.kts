plugins { id("kotlin-common-conventions") }
dependencies {
    implementation(project(":asset:port"))
    implementation(libs.spring.context)
}
