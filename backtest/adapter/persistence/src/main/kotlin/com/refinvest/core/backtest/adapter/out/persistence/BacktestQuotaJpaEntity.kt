package com.refinvest.core.backtest.adapter.out.persistence

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.io.Serializable
import java.time.LocalDate

@Entity
@Table(name = "backtest_quotas")
class BacktestQuotaJpaEntity(
    @EmbeddedId
    var id: BacktestQuotaJpaId,
    @Column(name = "used_count", nullable = false)
    var usedCount: Int,
    @Column(name = "active_count", nullable = false)
    var activeCount: Int,
)

@Embeddable
data class BacktestQuotaJpaId(
    @Column(name = "member_id", nullable = false)
    val memberId: Long,
    @Column(name = "quota_month", nullable = false)
    val quotaMonth: LocalDate,
) : Serializable
