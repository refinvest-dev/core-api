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
)

tasks.register("verifyAggregateModuleBoundaries") {
    group = "verification"
    description = "Verifies the refactored aggregate modules and their framework boundaries."
    doLast {
        aggregateModuleDependencies.forEach { (modulePath, allowed) ->
            val module = project(modulePath)
            val dependencies = module.configurations
                .filter { it.name in setOf("api", "implementation", "compileOnly", "runtimeOnly") }
                .flatMap { it.allDependencies.withType(ProjectDependency::class.java) }
                .map { it.path }.toSet()
            check(allowed.containsAll(dependencies)) {
                "$modulePath has forbidden project dependencies: ${dependencies - allowed}"
            }
            if (":adapter:" !in modulePath) {
                val technology = module.configurations.getByName("compileClasspath")
                    .incoming.resolutionResult.allComponents.mapNotNull { it.id as? ModuleComponentIdentifier }
                    .filter { id ->
                        id.group.startsWith("org.springframework.data") || id.group.startsWith("org.hibernate") ||
                            id.group.startsWith("jakarta.persistence") || id.group == "org.postgresql" ||
                            id.group.startsWith("org.springframework.boot") ||
                            (id.group == "org.springframework" && id.module in setOf("spring-web", "spring-webmvc", "spring-jdbc", "spring-orm")) ||
                            (!modulePath.endsWith(":application") && id.group.startsWith("org.springframework"))
                    }
                check(technology.isEmpty()) { "$modulePath exposes forbidden technology dependencies: $technology" }
            }
            if (modulePath.endsWith(":application") || modulePath == ":auth:adapter:security") {
                val ownDomain = ":${modulePath.split(':')[1]}:domain"
                listOf("compileClasspath", "testCompileClasspath").forEach { configuration ->
                    val foreignDomains = module.configurations.getByName(configuration).incoming.resolutionResult
                        .allComponents.mapNotNull { it.id as? ProjectComponentIdentifier }
                        .map { it.projectPath }.filter { it.endsWith(":domain") && it != ownDomain }
                    check(foreignDomains.isEmpty()) { "$modulePath $configuration exposes foreign domains: $foreignDomains" }
                }
            }
        }
    }
}

// Keep the original entry point for existing local verification scripts.
tasks.register("verifyMemberSubscriptionBoundaries") { dependsOn("verifyAggregateModuleBoundaries") }
