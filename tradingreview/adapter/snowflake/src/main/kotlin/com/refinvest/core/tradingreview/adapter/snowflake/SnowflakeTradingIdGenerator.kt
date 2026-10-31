package com.refinvest.core.tradingreview.adapter.snowflake

import com.refinvest.core.tradingreview.domain.valueobject.DeletionRequestId

import com.refinvest.core.shared.infrastructure.id.SnowflakeIdGenerator
import com.refinvest.core.tradingreview.domain.valueobject.TradingAccountId
import com.refinvest.core.tradingreview.domain.valueobject.TradingBookId
import com.refinvest.core.tradingreview.domain.valueobject.TradingImportSessionId
import com.refinvest.core.tradingreview.domain.valueobject.TradingSourceArtifactId
import com.refinvest.core.tradingreview.port.TradingIdGenerator
import org.springframework.stereotype.Component

@Component
class SnowflakeTradingIdGenerator(private val snowflake: SnowflakeIdGenerator) : TradingIdGenerator {
    override fun accountId() = TradingAccountId(snowflake.next())
    override fun bookId() = TradingBookId(snowflake.next())
    override fun importSessionId() = TradingImportSessionId(snowflake.next())
    override fun sourceArtifactId() = TradingSourceArtifactId(snowflake.next())
    override fun deletionId() = DeletionRequestId(snowflake.next())
}
