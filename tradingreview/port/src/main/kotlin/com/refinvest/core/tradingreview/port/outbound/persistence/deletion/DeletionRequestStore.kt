package com.refinvest.core.tradingreview.port.outbound.persistence.deletion

import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.tradingreview.domain.DeletionRequest
import com.refinvest.core.tradingreview.domain.valueobject.DeletionRequestId
import com.refinvest.core.tradingreview.domain.valueobject.DeletionScope

interface DeletionRequestStore {
    fun insert(request: DeletionRequest)
    fun find(id: DeletionRequestId, owner: MemberId): DeletionRequest?
    fun findByTarget(owner: MemberId, scope: DeletionScope): DeletionRequest?
}
