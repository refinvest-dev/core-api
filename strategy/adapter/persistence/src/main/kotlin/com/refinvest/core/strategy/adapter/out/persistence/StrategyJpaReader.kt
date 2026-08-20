package com.refinvest.core.strategy.adapter.out.persistence

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.repository.Repository

interface StrategyJpaReader : Repository<StrategyJpaEntity, Long> {
    fun findById(id: Long): StrategyJpaEntity?

    fun findAllByMemberIdOrderByCreatedAtDesc(memberId: Long, pageable: Pageable): Page<StrategyJpaEntity>
}
