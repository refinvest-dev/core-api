package com.refinvest.core.backtest.adapter.out.persistence

import com.refinvest.core.backtest.domain.backtest.BacktestResult

internal object BacktestResultPersistenceMapper {
    fun toEntity(result: BacktestResult, payloadMapper: BacktestResultPayloadMapper): BacktestResultJpaEntity =
        BacktestResultJpaEntity(result.backtestRunId.value, payloadMapper.serialize(result))

    fun toDomain(entity: BacktestResultJpaEntity, payloadMapper: BacktestResultPayloadMapper): BacktestResult =
        payloadMapper.deserialize(entity.resultPayload).also {
            require(it.backtestRunId.value == entity.backtestRunId) { "Result payload belongs to another BacktestRun" }
        }
}
