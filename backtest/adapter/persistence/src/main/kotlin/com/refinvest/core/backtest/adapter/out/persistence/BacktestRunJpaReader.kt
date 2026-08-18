package com.refinvest.core.backtest.adapter.out.persistence

import org.springframework.data.repository.Repository

interface BacktestRunJpaReader : Repository<BacktestRunJpaEntity, Long> {
    fun findById(id: Long): BacktestRunJpaEntity?
}
