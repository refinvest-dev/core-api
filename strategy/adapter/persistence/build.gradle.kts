plugins { id("jpa-adapter-conventions") }
dependencies {
    implementation(project(":strategy:port"))
    implementation(libs.spring.boot.starter.data.jpa)
}
