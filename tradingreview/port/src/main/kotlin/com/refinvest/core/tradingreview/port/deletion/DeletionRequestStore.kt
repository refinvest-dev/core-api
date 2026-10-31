package com.refinvest.core.tradingreview.port.deletion

import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.tradingreview.domain.DeletionRequest
import com.refinvest.core.tradingreview.domain.valueobject.DeletionRequestId
import com.refinvest.core.tradingreview.domain.valueobject.DeletionScope

/** Request admission and command restoration. Worker state CAS/checkpoint capabilities follow separately. */
interface DeletionRequestStore {
    fun insert(request: DeletionRequest)
    fun find(id: DeletionRequestId, owner: MemberId): DeletionRequest?
    fun findByTarget(owner: MemberId, scope: DeletionScope): DeletionRequest?
}
