package com.refinvest.core.backtest.adapter.out.persistence

import org.springframework.data.repository.Repository

interface BacktestResultJpaReader : Repository<BacktestResultJpaEntity, Long> {
    fun findById(backtestRunId: Long): BacktestResultJpaEntity?
}
