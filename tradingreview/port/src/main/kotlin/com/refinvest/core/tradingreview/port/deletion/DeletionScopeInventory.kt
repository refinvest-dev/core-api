package com.refinvest.core.tradingreview.port.deletion

import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.tradingreview.domain.valueobject.DeletionAffectedResources
import com.refinvest.core.tradingreview.domain.valueobject.DeletionScope

/** Called inside the deletion command transaction after Account -> Book -> Session locks. */
interface DeletionScopeInventory {
    fun capture(scope: DeletionScope, owner: MemberId): DeletionAffectedResources?
}
