package com.refinvest.core.strategy.adapter.out.persistence

import org.springframework.data.repository.CrudRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface StrategyJpaStore : CrudRepository<StrategyJpaEntity, Long> {
    // Lock before loading the eager collections so a waiter reads the preceding writer's commit.
    @Query(value = "SELECT id FROM strategies WHERE id = :id FOR UPDATE", nativeQuery = true)
    fun lockById(@Param("id") id: Long): Long?
}
