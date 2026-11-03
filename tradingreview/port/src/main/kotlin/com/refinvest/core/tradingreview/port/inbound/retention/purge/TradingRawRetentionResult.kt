package com.refinvest.core.tradingreview.port.inbound.retention.purge

data class TradingRawRetentionResult(val overdue: Long, val deleted: Int, val waiting: Int, val failures: Int)
