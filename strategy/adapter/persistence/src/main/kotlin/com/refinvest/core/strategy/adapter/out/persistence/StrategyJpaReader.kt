package com.refinvest.core.strategy.adapter.out.persistence

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.repository.Repository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface StrategyJpaReader : Repository<StrategyJpaEntity, Long> {
    @Query("SELECT strategy.memberId FROM StrategyJpaEntity strategy WHERE strategy.id = :id")
    fun findOwnerById(@Param("id") id: Long): Long?

    fun findById(id: Long): StrategyJpaEntity?

    fun findAllByMemberIdOrderByCreatedAtDesc(memberId: Long, pageable: Pageable): Page<StrategyJpaEntity>
}
