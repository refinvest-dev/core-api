package com.refinvest.core.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

@Configuration
class StrategyConfiguration {
    @Bean
    fun clock(): Clock = Clock.systemUTC()
}
