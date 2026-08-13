package com.refinvest.core.strategy.adapter.out.persistence

import org.springframework.data.repository.Repository

interface StrategyJpaReader : Repository<StrategyJpaEntity, Long> {
    fun findById(id: Long): StrategyJpaEntity?
}
