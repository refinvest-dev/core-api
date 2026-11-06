plugins { id("jpa-adapter-conventions") }
dependencies {
    implementation(project(":strategy:port"))
    implementation(libs.spring.boot.starter.data.jpa)
    testImplementation(project(":strategy:application"))
    testRuntimeOnly(libs.kotlin.reflect)
    testRuntimeOnly(libs.postgresql)
}
