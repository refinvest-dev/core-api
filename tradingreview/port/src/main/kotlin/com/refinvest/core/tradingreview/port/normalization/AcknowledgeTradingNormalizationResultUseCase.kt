package com.refinvest.core.tradingreview.port.normalization

/** Sends at most one committed outbox ACK, outside the database transaction. */
fun interface AcknowledgeTradingNormalizationResultUseCase {
    fun acknowledgeOne(): Boolean
}
