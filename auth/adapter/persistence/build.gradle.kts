plugins { id("jpa-adapter-conventions") }

dependencies {
    implementation(project(":auth:port"))
    implementation(libs.spring.boot.starter.data.jpa)
}
