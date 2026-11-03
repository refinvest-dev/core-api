package com.refinvest.core.tradingreview.port.outbound.persistence.deletion

import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.tradingreview.domain.valueobject.DeletionAffectedResources
import com.refinvest.core.tradingreview.domain.valueobject.DeletionScope

interface DeletionScopeInventory {
    fun capture(scope: DeletionScope, owner: MemberId): DeletionAffectedResources?
}
