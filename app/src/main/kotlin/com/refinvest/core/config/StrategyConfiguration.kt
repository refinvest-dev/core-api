package com.refinvest.core.config

import com.refinvest.core.strategy.domain.valueobject.MemberId
import com.refinvest.core.strategy.port.outbound.MemberIdProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

@Configuration
class StrategyConfiguration {
    @Bean
    fun clock(): Clock = Clock.systemUTC()

    @Bean
    fun testMemberIdProvider(
        @Value("\${refinvest.execution.test-member-id}") testMemberId: Long,
    ): MemberIdProvider = MemberIdProvider { MemberId(testMemberId) }

}
