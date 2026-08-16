package com.refinvest.core.backtest.adapter.out.persistence

import org.springframework.data.repository.CrudRepository

interface BacktestRunJpaStore : CrudRepository<BacktestRunJpaEntity, Long>
