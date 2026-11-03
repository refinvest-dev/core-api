package com.refinvest.core.tradingreview.port.outbound.persistence.book

import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.tradingreview.domain.valueobject.TradingAccountId
import java.time.Instant

interface TradingBookDeletionCascade {
    /** Requires the saved Account deletion state in the same transaction; null means a stale fence. */
    fun markDeleting(deletion: TradingAccountBookDeletion): Long?
}
