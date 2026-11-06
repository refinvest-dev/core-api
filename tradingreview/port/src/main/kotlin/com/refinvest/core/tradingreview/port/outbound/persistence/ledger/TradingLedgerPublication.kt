package com.refinvest.core.tradingreview.port.outbound.persistence.ledger

import com.refinvest.core.tradingreview.domain.LedgerRevision
import com.refinvest.core.tradingreview.domain.importsession.NormalizationRun
import com.refinvest.core.tradingreview.domain.importsession.NormalizationDispatch

interface TradingLedgerPublication {
    /** False leaves revision, membership, latest pointer and acknowledgement unchanged. */
    fun publish(publication: LedgerPublication): Boolean
}
