package com.refinvest.core.backtest.adapter.out.persistence

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.repository.Repository

interface BacktestRunJpaReader : Repository<BacktestRunJpaEntity, Long> {
    fun findById(id: Long): BacktestRunJpaEntity?

    fun findAllByStrategyIdOrderByCreatedAtDesc(strategyId: Long, pageable: Pageable): Page<BacktestRunJpaEntity>
}
