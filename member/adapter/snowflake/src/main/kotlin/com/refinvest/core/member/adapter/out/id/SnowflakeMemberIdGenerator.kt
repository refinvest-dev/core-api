package com.refinvest.core.member.adapter.out.id

import com.refinvest.core.member.port.outbound.MemberIdGenerator
import com.refinvest.core.shared.infrastructure.id.SnowflakeIdGenerator
import com.refinvest.core.shared.kernel.member.MemberId
import org.springframework.stereotype.Component

@Component
class SnowflakeMemberIdGenerator(
    private val snowflakeIdGenerator: SnowflakeIdGenerator,
) : MemberIdGenerator {
    override fun next(): MemberId = MemberId(snowflakeIdGenerator.next())
}
