package com.refinvest.core.tradingreview.port.inbound.deletion.get

import com.refinvest.core.tradingreview.port.inbound.deletion.TradingDeletion

interface GetTradingDeletionRequestUseCase { fun get(id: Long): TradingDeletion }
