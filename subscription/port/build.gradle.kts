plugins { id("kotlin-common-conventions") }

dependencies {
    api(project(":shared:kernel"))
    api(project(":subscription:domain"))
}
