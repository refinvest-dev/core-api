package com.refinvest.core.tradingreview.port.inbound.deletion.process

import com.refinvest.core.tradingreview.domain.DeletionRequest
import com.refinvest.core.tradingreview.domain.valueobject.*
import java.time.Duration
import java.time.Instant

interface TradingDeletionUseCase { fun purgePending() }
