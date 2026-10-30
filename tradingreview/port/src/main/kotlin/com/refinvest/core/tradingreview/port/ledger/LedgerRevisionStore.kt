package com.refinvest.core.tradingreview.port.ledger

import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.tradingreview.domain.LedgerRevision
import com.refinvest.core.tradingreview.domain.valueobject.LedgerRevisionId

/** Immutable command-side restoration; publication is the only write capability. */
interface LedgerRevisionStore {
    fun find(id: LedgerRevisionId, owner: MemberId): LedgerRevision?
}
