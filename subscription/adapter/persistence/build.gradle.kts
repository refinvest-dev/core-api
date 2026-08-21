plugins { id("jpa-adapter-conventions") }

dependencies {
    implementation(project(":subscription:port"))
    implementation(libs.spring.boot.starter.data.jpa)
}
