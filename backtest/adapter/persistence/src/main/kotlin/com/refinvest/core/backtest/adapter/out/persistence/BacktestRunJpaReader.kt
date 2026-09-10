package com.refinvest.core.backtest.adapter.out.persistence

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.Repository
import org.springframework.data.repository.query.Param
import java.time.Instant

interface BacktestRunJpaReader : Repository<BacktestRunJpaEntity, Long> {
    fun findById(id: Long): BacktestRunJpaEntity?
    fun findFirstByStrategyVersionIdAndStatusOrderByCreatedAtDesc(strategyVersionId: Long, status: BacktestRunStatusJpa): BacktestRunJpaEntity?

    fun findAllByStrategyIdOrderByCreatedAtDesc(strategyId: Long, pageable: Pageable): Page<BacktestRunJpaEntity>

    @Query(
        value = """
            select count(*)
            from backtest_runs run
            join strategies strategy on strategy.id = run.strategy_id
            where strategy.member_id = :memberId
              and run.created_at >= :startInclusive
              and run.created_at < :endExclusive
        """,
        nativeQuery = true,
    )
    fun countByMemberIdAndCreatedAtBetween(
        @Param("memberId") memberId: Long,
        @Param("startInclusive") startInclusive: Instant,
        @Param("endExclusive") endExclusive: Instant,
    ): Long
}
