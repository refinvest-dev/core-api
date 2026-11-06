package com.refinvest.core.tradingreview.port.outbound.idempotency

import com.refinvest.core.tradingreview.domain.*

sealed interface IdempotencyClaim {
    data object New : IdempotencyClaim
    data class Replayed(val resourceId: Long) : IdempotencyClaim
}
