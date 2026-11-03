package com.refinvest.core.tradingreview.port.outbound.persistence.ledger

import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.tradingreview.domain.LedgerRevision
import com.refinvest.core.tradingreview.domain.valueobject.LedgerRevisionId

interface LedgerRevisionStore {
    fun find(id: LedgerRevisionId, owner: MemberId): LedgerRevision?
}
