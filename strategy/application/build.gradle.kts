plugins { id("kotlin-common-conventions") }
dependencies {
    implementation(project(":strategy:port"))
    implementation(libs.spring.context)
}
