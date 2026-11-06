package com.refinvest.core.tradingreview.port.outbound.normalization.policy

import com.refinvest.core.tradingreview.domain.*

interface TradingRetryJitter { fun factor(): Double }
