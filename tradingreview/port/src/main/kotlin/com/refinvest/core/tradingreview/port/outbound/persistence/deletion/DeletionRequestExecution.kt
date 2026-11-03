package com.refinvest.core.tradingreview.port.outbound.persistence.deletion

import com.refinvest.core.tradingreview.domain.DeletionRequest

interface DeletionRequestExecution {
    fun lockNext(): DeletionRequest?
    fun commit(expected: DeletionRequest, updated: DeletionRequest): Boolean
}
