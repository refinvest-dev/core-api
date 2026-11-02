import org.gradle.jvm.tasks.Jar
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier

allprojects {
	group = if (path == ":") "com.refinvest" else "com.refinvest${path.replace(':', '.')}"
	version = "0.0.1-SNAPSHOT"

	tasks.withType<Jar>().configureEach {
		archiveBaseName.set(project.path.removePrefix(":").replace(':', '-'))
	}
}

// Inspect Gradle's dependency model as well as the source checks in :app.
val aggregateModuleDependencies = mapOf(
    ":member:domain" to setOf(":shared:kernel"),
    ":member:port" to setOf(":member:domain"),
    ":member:application" to setOf(":member:port", ":subscription:port"),
    ":member:adapter:persistence" to setOf(":member:port"),
    ":member:adapter:snowflake" to setOf(":member:port", ":shared:infrastructure"),
    ":subscription:domain" to setOf(":shared:kernel"),
    ":subscription:port" to setOf(":subscription:domain", ":shared:kernel"),
    ":subscription:application" to setOf(":subscription:port", ":backtest:port"),
    ":subscription:adapter:persistence" to setOf(":subscription:port"),
    ":auth:domain" to setOf(":shared:kernel"),
    ":auth:port" to setOf(":auth:domain"),
    ":auth:application" to setOf(":auth:port", ":member:port"),
    ":auth:adapter:persistence" to setOf(":auth:port"),
    ":auth:adapter:security" to setOf(":auth:port", ":member:port", ":strategy:port", ":backtest:port"),
    // Cookie transport is shared with the OAuth success handler in the same feature.
    ":auth:adapter:web" to setOf(":auth:port", ":auth:adapter:security", ":subscription:port"),
    ":strategy:domain" to setOf(":shared:kernel"),
    ":strategy:port" to setOf(":strategy:domain"),
    ":strategy:application" to setOf(":strategy:port"),
    ":strategy:adapter:persistence" to setOf(":strategy:port"),
    ":strategy:adapter:web" to setOf(":strategy:port"),
    ":strategy:adapter:snowflake" to setOf(":strategy:port", ":shared:infrastructure"),
    ":backtest:domain" to setOf(":shared:kernel"),
    ":backtest:port" to setOf(":backtest:domain"),
    ":backtest:application" to setOf(":backtest:port", ":strategy:port", ":subscription:port"),
    ":backtest:adapter:persistence" to setOf(":backtest:port"),
    ":backtest:adapter:compute" to setOf(":backtest:port", ":shared:infrastructure"),
    ":backtest:adapter:web" to setOf(":backtest:port"),
    ":backtest:adapter:snowflake" to setOf(":backtest:port", ":shared:infrastructure"),
    ":asset:domain" to emptySet(),
    ":asset:port" to setOf(":asset:domain"),
    ":asset:application" to setOf(":asset:port"),
    ":asset:adapter:web" to setOf(":asset:port"),
    ":asset:adapter:compute" to setOf(":asset:port", ":shared:infrastructure"),
    ":tradingreview:domain" to setOf(":shared:kernel"),
    ":tradingreview:port" to setOf(":tradingreview:domain"),
    ":tradingreview:application" to setOf(":tradingreview:port"),
    ":tradingreview:adapter:persistence" to setOf(":tradingreview:port"),
    ":tradingreview:adapter:web" to setOf(":tradingreview:port"),
    ":tradingreview:adapter:compute" to setOf(":tradingreview:port"),
    ":tradingreview:adapter:storage" to setOf(":tradingreview:port"),
    ":tradingreview:adapter:uuid" to setOf(":tradingreview:port"),
    ":tradingreview:adapter:security" to setOf(":tradingreview:port", ":member:port"),
    ":tradingreview:adapter:snowflake" to setOf(":tradingreview:port", ":shared:infrastructure"),
    ":shared:kernel" to emptySet(),
    ":shared:infrastructure" to emptySet(),
    ":app" to setOf(
        ":member:application", ":member:adapter:persistence", ":member:adapter:snowflake",
        ":subscription:application", ":subscription:adapter:persistence",
        ":auth:application", ":auth:adapter:persistence", ":auth:adapter:security", ":auth:adapter:web",
        ":strategy:application", ":strategy:port", ":strategy:adapter:persistence", ":strategy:adapter:snowflake", ":strategy:adapter:web",
        ":backtest:application", ":backtest:port", ":backtest:adapter:persistence", ":backtest:adapter:compute", ":backtest:adapter:snowflake", ":backtest:adapter:web",
        ":asset:application", ":asset:adapter:web", ":asset:adapter:compute",
        ":tradingreview:application", ":tradingreview:port", ":tradingreview:adapter:persistence", ":tradingreview:adapter:web",
        ":tradingreview:adapter:compute", ":tradingreview:adapter:storage", ":tradingreview:adapter:security",
        ":tradingreview:adapter:snowflake", ":tradingreview:adapter:uuid", ":shared:infrastructure",
    ),
)

tasks.register("verifyAggregateModuleBoundaries") {
    group = "verification"
    description = "Verifies every configured module and its framework boundaries."
    doLast {
        val modules = subprojects.filter { it.buildFile.isFile }.map { it.path }.toSet()
        check(modules == aggregateModuleDependencies.keys) {
            "Module boundary coverage mismatch: missing=${modules - aggregateModuleDependencies.keys}, " +
                "obsolete=${aggregateModuleDependencies.keys - modules}"
        }
        aggregateModuleDependencies.forEach { (modulePath, allowed) ->
            val module = project(modulePath)
            val dependencies = module.configurations
                .filter { it.name in setOf("api", "implementation", "compileOnly", "runtimeOnly") }
                .flatMap { it.allDependencies.withType(ProjectDependency::class.java) }
                .map { it.path }.toSet()
            check(allowed.containsAll(dependencies)) {
                "$modulePath has forbidden project dependencies: ${dependencies - allowed}"
            }
            if (modulePath != ":app") {
                listOf("compileClasspath", "testCompileClasspath").forEach { configuration ->
                    val applications = module.configurations.getByName(configuration).incoming.resolutionResult
                        .allComponents.mapNotNull { it.id as? ProjectComponentIdentifier }
                        .map { it.projectPath }.filter { it.endsWith(":application") && it != modulePath &&
                            (configuration == "compileClasspath" || it.split(':')[1] != modulePath.split(':')[1]) }
                    check(applications.isEmpty()) { "$modulePath $configuration exposes application implementations: $applications" }
                }
            }
            if (modulePath.endsWith(":domain") || modulePath.endsWith(":port") ||
                modulePath.endsWith(":application") || modulePath == ":shared:kernel") {
                val technology = listOf("compileClasspath", "runtimeClasspath").flatMap { configuration ->
                    module.configurations.getByName(configuration)
                    .incoming.resolutionResult.allComponents.mapNotNull { it.id as? ModuleComponentIdentifier }
                }.distinct()
                    .filter { id ->
                        id.group.startsWith("org.springframework.data") || id.group.startsWith("org.hibernate") ||
                            id.group.startsWith("jakarta.persistence") || id.group == "org.postgresql" ||
                            (id.group.startsWith("org.springframework.boot") && id.module != "spring-boot-dependencies") ||
                            (id.group == "org.springframework" && id.module in setOf("spring-web", "spring-webmvc", "spring-jdbc", "spring-orm")) ||
                            (!modulePath.endsWith(":application") && id.group.startsWith("org.springframework"))
                    }
                check(technology.isEmpty()) { "$modulePath exposes forbidden technology dependencies: $technology" }
            }
            if (modulePath.startsWith(":shared:")) {
                listOf("compileClasspath", "testCompileClasspath").forEach { configuration ->
                    val features = module.configurations.getByName(configuration).incoming.resolutionResult
                        .allComponents.mapNotNull { it.id as? ProjectComponentIdentifier }
                        .map { it.projectPath }.filter { !it.startsWith(":shared:") }
                    check(features.isEmpty()) { "$modulePath $configuration exposes feature implementations: $features" }
                }
            } else if (modulePath != ":app") {
                val ownDomain = ":${modulePath.split(':')[1]}:domain"
                listOf("compileClasspath", "testCompileClasspath").forEach { configuration ->
                    val foreignDomains = module.configurations.getByName(configuration).incoming.resolutionResult
                        .allComponents.mapNotNull { it.id as? ProjectComponentIdentifier }
                        .map { it.projectPath }.filter { it.endsWith(":domain") && it != ownDomain }
                    check(foreignDomains.isEmpty()) { "$modulePath $configuration exposes foreign domains: $foreignDomains" }
                }
            }
        }
        logger.lifecycle("Verified module boundaries for ${modules.size} modules.")
    }
}

// Keep the original entry point for existing local verification scripts.
tasks.register("verifyMemberSubscriptionBoundaries") { dependsOn("verifyAggregateModuleBoundaries") }
