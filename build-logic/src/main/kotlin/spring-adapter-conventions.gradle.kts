import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    id("kotlin-common-conventions")
    kotlin("plugin.spring")
    id("io.spring.dependency-management")
}

dependencyManagement {
    imports {
        mavenBom(SpringBootPlugin.BOM_COORDINATES)
    }
}
