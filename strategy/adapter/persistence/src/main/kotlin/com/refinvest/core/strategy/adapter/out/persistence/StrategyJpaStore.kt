package com.refinvest.core.strategy.adapter.out.persistence

import org.springframework.data.repository.CrudRepository

interface StrategyJpaStore : CrudRepository<StrategyJpaEntity, Long>
