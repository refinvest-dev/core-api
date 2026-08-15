package com.refinvest.core.strategy.adapter.out.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table

@Entity
@Table(name = "strategy_version_conditions")
class ConditionJpaEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    @ManyToOne
    @JoinColumn(name = "strategy_version_id", nullable = false)
    var strategyVersion: StrategyVersionJpaEntity,
    @Column(name = "condition_order", nullable = false)
    var conditionOrder: Int,
    @Column(nullable = false)
    var operator: String,
    @Column(name = "logical_combinator")
    var logicalCombinator: String?,
    @Column(name = "operand_a_asset", nullable = false)
    var operandAAsset: String,
    @Column(name = "operand_a_metric", nullable = false)
    var operandAMetric: String,
    @Column(name = "operand_a_window")
    var operandAWindow: Int?,
    @Column(name = "operand_b_kind", nullable = false)
    var operandBKind: String,
    @Column(name = "operand_b_literal")
    var operandBLiteral: Double?,
    @Column(name = "operand_b_asset")
    var operandBAsset: String?,
    @Column(name = "operand_b_metric")
    var operandBMetric: String?,
    @Column(name = "operand_b_window")
    var operandBWindow: Int?,
)
