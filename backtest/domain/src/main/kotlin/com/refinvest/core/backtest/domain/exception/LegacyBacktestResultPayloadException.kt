package com.refinvest.core.backtest.domain.exception

class LegacyBacktestResultPayloadException : IllegalStateException(
    "Backtest result uses an unsupported legacy payload",
)
