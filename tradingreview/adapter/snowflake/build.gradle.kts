plugins { id("spring-adapter-conventions") }
dependencies {
    implementation(project(":tradingreview:port"))
    implementation(project(":shared:infrastructure"))
    implementation(libs.spring.context)
}
