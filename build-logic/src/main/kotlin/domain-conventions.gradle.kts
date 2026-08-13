import org.gradle.api.GradleException

plugins {
    id("kotlin-common-conventions")
}

val forbiddenFrameworkGroups = listOf(
    "org.springframework",
    "jakarta.persistence",
    "javax.persistence",
    "org.hibernate",
    "org.jetbrains.exposed",
    "io.micronaut",
    "io.quarkus",
)

configurations.configureEach {
    withDependencies {
        val forbidden = filter { dependency ->
            dependency.group?.let { group ->
                forbiddenFrameworkGroups.any { forbiddenGroup ->
                    group == forbiddenGroup || group.startsWith("$forbiddenGroup.")
                }
            } == true
        }
        if (forbidden.isNotEmpty()) {
            throw GradleException(
                "Domain module $path must not depend on frameworks: " +
                    forbidden.joinToString { "${it.group}:${it.name}" },
            )
        }
    }
}
