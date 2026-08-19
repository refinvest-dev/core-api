package com.refinvest.core.backtest.adapter.out.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Lob
import jakarta.persistence.Table

@Entity
@Table(name = "backtest_results")
class BacktestResultJpaEntity(
    @Id
    @Column(name = "backtest_run_id")
    var backtestRunId: Long,
    @Lob
    @Column(name = "result_payload", nullable = false)
    var resultPayload: String,
)
