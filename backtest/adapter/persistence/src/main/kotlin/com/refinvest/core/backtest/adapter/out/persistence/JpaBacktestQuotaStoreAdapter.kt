package com.refinvest.core.backtest.adapter.out.persistence

import com.refinvest.core.backtest.port.outbound.persistence.BacktestQuotaStore
import com.refinvest.core.backtest.port.outbound.persistence.BacktestQuotaReservation
import com.refinvest.core.shared.kernel.member.MemberId
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.time.YearMonth

@Repository
class JpaBacktestQuotaStoreAdapter(
    private val backtestQuotaJpaStore: BacktestQuotaJpaStore,
    private val jdbcTemplate: JdbcTemplate,
) : BacktestQuotaStore {
    override fun reserve(
        memberId: MemberId,
        quotaMonth: YearMonth,
        monthlyExecutionLimit: Int,
        maxConcurrentRuns: Int,
    ): BacktestQuotaReservation {
        val id = BacktestQuotaJpaId(memberId.value, quotaMonth.atDay(1))
        if (reserveAtomically(memberId.value, quotaMonth.atDay(1), monthlyExecutionLimit, maxConcurrentRuns)) {
            return BacktestQuotaReservation.RESERVED
        }
        val quota = requireNotNull(backtestQuotaJpaStore.findById(id))
        return if (quota.activeCount >= maxConcurrentRuns) {
            BacktestQuotaReservation.CONCURRENCY_LIMIT_EXCEEDED
        } else {
            BacktestQuotaReservation.MONTHLY_LIMIT_EXCEEDED
        }
    }

    override fun releaseConcurrentCapacity(memberId: MemberId, quotaMonth: YearMonth) {
        backtestQuotaJpaStore.releaseConcurrentCapacity(memberId.value, quotaMonth.atDay(1))
    }

    private fun reserveAtomically(
        memberId: Long,
        quotaMonth: java.time.LocalDate,
        monthlyExecutionLimit: Int,
        maxConcurrentRuns: Int,
    ): Boolean = if (isH2()) {
        reserveForH2(memberId, quotaMonth, monthlyExecutionLimit, maxConcurrentRuns)
    } else {
        jdbcTemplate.update(
            """
                insert into backtest_quotas (member_id, quota_month, used_count, active_count)
                values (?, ?, 1, 1)
                on conflict (member_id, quota_month) do update
                set used_count = backtest_quotas.used_count + 1,
                    active_count = backtest_quotas.active_count + 1
                where backtest_quotas.used_count < ?
                  and backtest_quotas.active_count < ?
            """.trimIndent(),
            memberId, quotaMonth, monthlyExecutionLimit, maxConcurrentRuns,
        ) == 1
    }

    private fun reserveForH2(memberId: Long, quotaMonth: java.time.LocalDate, monthlyLimit: Int, concurrentLimit: Int): Boolean {
        val updated = jdbcTemplate.update(
            """update backtest_quotas set used_count = used_count + 1, active_count = active_count + 1
                where member_id = ? and quota_month = ? and used_count < ? and active_count < ?""",
            memberId, quotaMonth, monthlyLimit, concurrentLimit,
        )
        if (updated == 1) return true
        if (backtestQuotaJpaStore.findById(BacktestQuotaJpaId(memberId, quotaMonth)) != null) return false
        jdbcTemplate.update(
            "insert into backtest_quotas (member_id, quota_month, used_count, active_count) values (?, ?, 1, 1)",
            memberId, quotaMonth,
        )
        return true
    }

    private fun isH2(): Boolean = jdbcTemplate.dataSource!!.connection.use { connection ->
        connection.metaData.databaseProductName.equals("H2", ignoreCase = true)
    }
}
