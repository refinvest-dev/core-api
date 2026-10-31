package com.refinvest.core.tradingreview.port.deletion

import com.refinvest.core.tradingreview.domain.DeletionRequest

/** Execution capability; callers hold one transaction through claim, local work and CAS/checkpoints. */
interface DeletionRequestExecution {
    fun lockNext(): DeletionRequest?
    fun commit(expected: DeletionRequest, updated: DeletionRequest): Boolean
}
