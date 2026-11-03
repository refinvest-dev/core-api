package com.refinvest.core.tradingreview.port.normalization.policy

import com.refinvest.core.tradingreview.domain.*

interface TradingRetryJitter { fun factor(): Double }
