package com.refinvest.core.backtest.adapter.out.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDate

/** Per-member serialization row shared by admission and release. */
@Entity
@Table(name = "backtest_capacities")
class BacktestCapacityJpaEntity(@Id @Column(name = "member_id") var memberId: Long)

/** Only active reservations are retained. The owning Run is stored in the same admission transaction. */
@Entity
@Table(name = "backtest_capacity_reservations")
class BacktestCapacityReservationJpaEntity(
    @Id @Column(name = "backtest_run_id") var backtestRunId: Long,
    @Column(name = "member_id", nullable = false) var memberId: Long,
    @Column(name = "quota_month", nullable = false) var quotaMonth: LocalDate,
)
