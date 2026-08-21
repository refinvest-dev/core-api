plugins { id("spring-adapter-conventions") }

dependencies {
    implementation(project(":member:port"))
    implementation(project(":shared:infrastructure"))
    implementation(libs.spring.context)
}
