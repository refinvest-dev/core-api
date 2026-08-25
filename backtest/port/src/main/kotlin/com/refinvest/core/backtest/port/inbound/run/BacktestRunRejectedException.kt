package com.refinvest.core.backtest.port.inbound.run

sealed class BacktestRunRejectedException(
    val code: String,
    message: String,
) : RuntimeException(message)

class BacktestPeriodNotAllowedException : BacktestRunRejectedException(
    code = "BACKTEST_PERIOD_NOT_ALLOWED",
    message = "Requested backtest period is not available for the current plan.",
)

class AssetNotAllowedForPlanException : BacktestRunRejectedException(
    code = "ASSET_NOT_ALLOWED_FOR_PLAN",
    message = "Requested asset is not available for the current plan.",
)

class BacktestMonthlyLimitExceededException : BacktestRunRejectedException(
    code = "BACKTEST_MONTHLY_LIMIT_EXCEEDED",
    message = "Monthly backtest execution limit exceeded.",
)

class BacktestConcurrencyLimitExceededException : BacktestRunRejectedException(
    code = "BACKTEST_CONCURRENCY_LIMIT_EXCEEDED",
    message = "Concurrent backtest execution limit exceeded.",
)
