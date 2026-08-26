package com.refinvest.core.config

import com.refinvest.core.shared.infrastructure.id.SnowflakeIdGenerator
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class IdGenerationConfiguration {
    @Bean
    fun snowflakeIdGenerator(
        @Value("\${refinvest.id.node-id}") nodeId: Long,
    ): SnowflakeIdGenerator = SnowflakeIdGenerator(nodeId)
}
