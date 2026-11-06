package com.refinvest.core.backtest.adapter.out.persistence

import com.refinvest.core.backtest.domain.valueobject.BacktestRunId
import com.refinvest.core.backtest.port.outbound.persistence.BacktestQuotaRequest
import com.refinvest.core.backtest.port.outbound.persistence.BacktestQuotaReservation
import com.refinvest.core.backtest.port.outbound.persistence.BacktestQuotaStore
import org.springframework.jdbc.core.ConnectionCallback
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.support.TransactionSynchronizationManager

@Repository
class JdbcBacktestQuotaStoreAdapter(private val jdbc: JdbcTemplate) : BacktestQuotaStore {
    override fun reserve(request: BacktestQuotaRequest): BacktestQuotaReservation {
        requireTransaction()
        val memberId = request.memberId.value
        lockMember(memberId)
        val month = request.quotaMonth.atDay(1)
        val existing = jdbc.query("SELECT member_id, quota_month FROM backtest_capacity_reservations WHERE backtest_run_id=?",
            { rs, _ -> rs.getLong(1) to rs.getDate(2).toLocalDate() }, request.backtestRunId.value).firstOrNull()
        if (existing != null) {
            require(existing == (memberId to month)) { "Capacity reservation belongs to another owner/month" }
            return BacktestQuotaReservation.RESERVED
        }
        val active = jdbc.queryForObject("SELECT count(*) FROM backtest_capacity_reservations WHERE member_id=?",
            Long::class.java, memberId) ?: 0L
        if (active >= request.maxConcurrentRuns) return BacktestQuotaReservation.CONCURRENCY_LIMIT_EXCEEDED
        val used = jdbc.query("SELECT used_count FROM backtest_quotas WHERE member_id=? AND quota_month=?",
            { rs, _ -> rs.getInt(1) }, memberId, month).firstOrNull()
        if (used != null && used >= request.monthlyExecutionLimit)
            return BacktestQuotaReservation.MONTHLY_LIMIT_EXCEEDED
        if (used == null) {
            jdbc.update("INSERT INTO backtest_quotas(member_id,quota_month,used_count,active_count) VALUES (?,?,1,0)", memberId, month)
        } else {
            jdbc.update("UPDATE backtest_quotas SET used_count=used_count+1 WHERE member_id=? AND quota_month=?", memberId, month)
        }
        jdbc.update("INSERT INTO backtest_capacity_reservations(backtest_run_id,member_id,quota_month) VALUES (?,?,?)",
            request.backtestRunId.value, memberId, month)
        return BacktestQuotaReservation.RESERVED
    }

    override fun releaseConcurrentCapacity(backtestRunId: BacktestRunId) {
        requireTransaction()
        val memberId = jdbc.query("SELECT member_id FROM backtest_capacity_reservations WHERE backtest_run_id=?",
            { rs, _ -> rs.getLong(1) }, backtestRunId.value).firstOrNull() ?: return
        lockMember(memberId)
        jdbc.update("DELETE FROM backtest_capacity_reservations WHERE backtest_run_id=?", backtestRunId.value)
    }

    private fun lockMember(memberId: Long) {
        val h2 = jdbc.execute(ConnectionCallback { it.metaData.databaseProductName.equals("H2", ignoreCase = true) }) == true
        if (h2) {
            jdbc.update("MERGE INTO backtest_capacities(member_id) KEY(member_id) VALUES (?)", memberId)
        } else {
            jdbc.update("INSERT INTO backtest_capacities(member_id) VALUES (?) ON CONFLICT DO NOTHING", memberId)
        }
        jdbc.queryForObject("SELECT member_id FROM backtest_capacities WHERE member_id=? FOR UPDATE", Long::class.java, memberId)
    }

    private fun requireTransaction() {
        check(TransactionSynchronizationManager.isActualTransactionActive()) { "Backtest quota requires an application transaction" }
    }
}
