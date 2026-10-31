package com.refinvest.core.tradingreview.port.ledger

import com.refinvest.core.tradingreview.domain.LedgerRevision
import com.refinvest.core.tradingreview.domain.importsession.NormalizationRun
import com.refinvest.core.tradingreview.domain.importsession.NormalizationDispatch
import com.refinvest.core.tradingreview.port.NormalizationResultDescriptor
import com.refinvest.core.tradingreview.port.normalization.NormalizationDispatchLease

/** A verified immutable revision plus its exact execution and live publication lease. */
data class LedgerPublication(
    val revision: LedgerRevision,
    val run: NormalizationRun,
    val dispatch: NormalizationDispatch,
    val descriptor: NormalizationResultDescriptor,
    val lease: NormalizationDispatchLease,
)

interface TradingLedgerPublication {
    /** False leaves revision, membership, latest pointer and acknowledgement unchanged. */
    fun publish(publication: LedgerPublication): Boolean
}
