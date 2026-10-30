plugins { id("spring-adapter-conventions") }
dependencies {
    implementation(project(":tradingreview:port"))
    implementation(libs.spring.context)
}
