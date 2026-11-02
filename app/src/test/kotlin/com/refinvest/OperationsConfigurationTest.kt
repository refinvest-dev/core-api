package com.refinvest

import com.refinvest.core.config.IdGenerationConfiguration
import com.refinvest.core.shared.infrastructure.id.SnowflakeIdGenerator
import org.junit.jupiter.api.Test
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.io.FileSystemResource
import kotlin.test.*

class OperationsConfigurationTest {
    // Load the executable's config: test application.yaml deliberately supplies its own node ID.
    private fun runner() = ApplicationContextRunner()
        .withInitializer { context ->
            context.environment.propertySources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)
            context.environment.propertySources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME)
            YamlPropertySourceLoader().load("executable", FileSystemResource("src/main/resources/application.yaml"))
                .forEach { context.environment.propertySources.addLast(it) }
        }
        .withBean(PropertySourcesPlaceholderConfigurer::class.java)
        .withUserConfiguration(IdGenerationConfiguration::class.java)

    @Test
    fun `missing node id prevents startup instead of choosing node zero`() {
        runner().run { context -> assertNotNull(context.startupFailure) }
    }

    @Test
    fun `blank non numeric and out of range node ids prevent startup`() {
        for (value in listOf("", "not-a-number", "-1", "1024")) {
            runner().withPropertyValues("SNOWFLAKE_NODE_ID=$value").run { context ->
                assertNotNull(context.startupFailure, "node ID '$value' must not start")
            }
        }
    }

    @Test
    fun `explicit node ids at both supported boundaries start and generate ids`() {
        for (value in listOf(0, 1023)) {
            runner().withPropertyValues("SNOWFLAKE_NODE_ID=$value").run { context ->
                assertNull(context.startupFailure)
                assertTrue(context.getBean(SnowflakeIdGenerator::class.java).next() > 0)
            }
        }
    }
}
