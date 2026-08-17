package com.refinvest.core.strategy.domain

import com.refinvest.core.common.domain.AggregateRoot
import com.refinvest.core.strategy.domain.strategy.StrategyVersion
import com.refinvest.core.strategy.domain.valueobject.MemberId
import com.refinvest.core.strategy.domain.valueobject.StrategyId
import java.time.Instant

class Strategy private constructor(
    override val id: StrategyId,
    val memberId: MemberId,
    val name: String,
    val createdAt: Instant,
    versions: List<StrategyVersion>,
) : AggregateRoot<StrategyId>(id) {
    private val mutableVersions = versions.toMutableList()
    val versions: List<StrategyVersion>
        get() = mutableVersions.toList()

    fun addVersion(version: StrategyVersion) {
        require(version.strategyId == id) { "StrategyVersion belongs to another Strategy" }
        mutableVersions += version
    }

    companion object {
        fun create(
            id: StrategyId,
            memberId: MemberId,
            name: String,
            createdAt: Instant,
        ): Strategy {
            require(name.isNotBlank()) { "Strategy name must not be blank" }
            require(name.length <= MAX_NAME_LENGTH) { "Strategy name must not exceed $MAX_NAME_LENGTH characters" }
            return Strategy(
                id = id,
                memberId = memberId,
                name = name,
                createdAt = createdAt,
                versions = emptyList(),
            )
        }

        private const val MAX_NAME_LENGTH = 200
    }
}
