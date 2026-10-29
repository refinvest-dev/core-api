package com.refinvest.core.backtest.adapter.out.persistence

import org.springframework.data.repository.CrudRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface BacktestRunJpaStore : CrudRepository<BacktestRunJpaEntity, Long> {
    @Query(value = "SELECT id FROM backtest_runs WHERE id=:id FOR UPDATE", nativeQuery = true)
    fun lockById(@Param("id") id: Long): Long?
}
