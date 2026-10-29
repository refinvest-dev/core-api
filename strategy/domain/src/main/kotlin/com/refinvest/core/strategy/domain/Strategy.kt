package com.refinvest.core.strategy.domain

import com.refinvest.core.common.domain.AggregateRoot
import com.refinvest.core.strategy.domain.strategy.StrategyVersion
import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.strategy.domain.valueobject.StrategyId
import java.time.Instant

class Strategy private constructor(
    id: StrategyId,
    val memberId: MemberId,
    val name: String,
    val createdAt: Instant,
    versions: List<StrategyVersion>,
) : AggregateRoot<StrategyId>(id) {
    private val mutableVersions = versions.toMutableList()
    val versions: List<StrategyVersion>
        get() = mutableVersions.toList()

    init {
        require(name.isNotBlank()) { "Strategy name must not be blank" }
        require(name.length <= MAX_NAME_LENGTH) { "Strategy name must not exceed $MAX_NAME_LENGTH characters" }
        require(mutableVersions.all { it.strategyId == id }) { "StrategyVersion belongs to another Strategy" }
        require(mutableVersions.map { it.id }.distinct().size == mutableVersions.size) { "StrategyVersion identity must be unique" }
    }

    fun addVersion(version: StrategyVersion) {
        require(version.strategyId == id) { "StrategyVersion belongs to another Strategy" }
        require(mutableVersions.none { it.id == version.id }) { "StrategyVersion identity must be unique" }
        mutableVersions += version
    }

    companion object {
        fun create(
            id: StrategyId,
            memberId: MemberId,
            name: String,
            createdAt: Instant,
        ): Strategy {
            return Strategy(
                id = id,
                memberId = memberId,
                name = name,
                createdAt = createdAt,
                versions = emptyList(),
            )
        }

        fun restore(id: StrategyId, memberId: MemberId, name: String, createdAt: Instant,
                    versions: List<StrategyVersion>): Strategy = Strategy(id, memberId, name, createdAt, versions)

        private const val MAX_NAME_LENGTH = 200
    }
}
