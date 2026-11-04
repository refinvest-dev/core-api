package com.refinvest.core.tradingreview.application

import com.refinvest.core.tradingreview.port.outbound.normalization.policy.TradingRetryJitter
import kotlin.random.Random
import org.springframework.stereotype.Service

@Service
class DefaultTradingRetryJitter : TradingRetryJitter {
    override fun factor(): Double = Random.nextDouble()
}
