plugins { id("jpa-adapter-conventions") }

dependencies {
    implementation(project(":member:port"))
    implementation(libs.spring.boot.starter.data.jpa)
}
