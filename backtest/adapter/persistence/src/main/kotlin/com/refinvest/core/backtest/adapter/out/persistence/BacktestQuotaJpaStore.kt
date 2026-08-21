package com.refinvest.core.backtest.adapter.out.persistence

import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.Repository
import org.springframework.data.repository.query.Param
import java.time.LocalDate

interface BacktestQuotaJpaStore : Repository<BacktestQuotaJpaEntity, BacktestQuotaJpaId> {
    fun findById(id: BacktestQuotaJpaId): BacktestQuotaJpaEntity?

    @Modifying
    @Query(
        value = """
            insert into backtest_quotas (member_id, quota_month, used_count, active_count)
            values (:memberId, :quotaMonth, 1, 1)
            on conflict (member_id, quota_month) do update
            set used_count = backtest_quotas.used_count + 1,
                active_count = backtest_quotas.active_count + 1
            where backtest_quotas.used_count < :monthlyExecutionLimit
              and backtest_quotas.active_count < :maxConcurrentRuns
        """,
        nativeQuery = true,
    )
    fun reserve(
        @Param("memberId") memberId: Long,
        @Param("quotaMonth") quotaMonth: LocalDate,
        @Param("monthlyExecutionLimit") monthlyExecutionLimit: Int,
        @Param("maxConcurrentRuns") maxConcurrentRuns: Int,
    ): Int

    @Modifying
    @Query(
        value = """
            update backtest_quotas
            set active_count = active_count - 1
            where member_id = :memberId
              and quota_month = :quotaMonth
              and active_count > 0
        """,
        nativeQuery = true,
    )
    fun releaseConcurrentCapacity(
        @Param("memberId") memberId: Long,
        @Param("quotaMonth") quotaMonth: LocalDate,
    ): Int
}
