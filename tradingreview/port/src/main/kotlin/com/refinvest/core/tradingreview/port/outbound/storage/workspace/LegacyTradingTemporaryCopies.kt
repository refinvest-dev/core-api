package com.refinvest.core.tradingreview.port.outbound.storage.workspace

import com.refinvest.core.tradingreview.domain.valueobject.*
import java.time.Duration
import java.time.Instant

interface LegacyTradingTemporaryCopies {
    fun absent(): Boolean
}
