package com.refinvest.core.tradingreview.adapter.snowflake

import com.refinvest.core.shared.infrastructure.id.SnowflakeIdGenerator
import com.refinvest.core.tradingreview.domain.TradingAccountId
import com.refinvest.core.tradingreview.domain.TradingBookId
import com.refinvest.core.tradingreview.port.TradingIdGenerator
import org.springframework.stereotype.Component

@Component
class SnowflakeTradingIdGenerator(private val snowflake: SnowflakeIdGenerator) : TradingIdGenerator {
    override fun accountId() = TradingAccountId(snowflake.next())
    override fun bookId() = TradingBookId(snowflake.next())
    override fun deletionId() = snowflake.next()
}
