package com.refinvest.core.strategy.adapter.out.persistence

import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.OneToMany
import jakarta.persistence.OrderBy
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "strategy_versions")
class StrategyVersionJpaEntity(
    @Id
    var id: Long,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "strategy_id", nullable = false)
    var strategy: StrategyJpaEntity,
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant,
    @Column(name = "primary_signal_asset", nullable = false)
    var primarySignalAsset: String,
    @Column(name = "execution_asset", nullable = false)
    var executionAsset: String,
    @Column(nullable = false)
    var lag: Int,
    @Column(name = "holding_signal_sessions", nullable = false)
    var holdingSignalSessions: Int,
    @OneToMany(mappedBy = "strategyVersion", cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.EAGER)
    @OrderBy("conditionOrder")
    var conditions: MutableList<ConditionJpaEntity> = mutableListOf(),
)
