package com.refinvest.core.strategy.adapter.out.persistence

import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.repository.Repository

interface StrategyVersionJpaReader : Repository<StrategyVersionJpaEntity, Long> {
    @EntityGraph(attributePaths = ["strategy"])
    fun findById(id: Long): StrategyVersionJpaEntity?
}
